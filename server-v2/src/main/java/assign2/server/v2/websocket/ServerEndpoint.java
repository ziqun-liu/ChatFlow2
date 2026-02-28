package assign2.server.v2.websocket;

import assign2.server.v2.model.ChatMessageDto;
import assign2.server.v2.model.ChatResponse;
import assign2.server.v2.model.QueueMessage;
import assign2.server.v2.rabbitmq.ChannelPool;
import assign2.server.v2.rabbitmq.MessagePublisher;
import assign2.server.v2.room.RoomManager;
import java.io.IOException;
import java.util.List;
import java.util.logging.Logger;
import javax.websocket.*;
import javax.websocket.server.PathParam;

/**
 * WebSocket server endpoint.
 *
 * Assignment 2 responsibilities:
 *   onOpen/onClose  — maintain rooms map (needed by BroadcastServlet)
 *   onMessage       — validate, publish to RabbitMQ, ACK sender directly
 *
 * Broadcast is no longer done here — it is triggered by BroadcastServlet
 * when the Consumer sends an HTTP POST after consuming from RabbitMQ.
 */
@javax.websocket.server.ServerEndpoint("/chat/{roomId}")
public class ServerEndpoint {

  private static final Logger logger = Logger.getLogger(ServerEndpoint.class.getName());

  // Lazily initialized on first message — avoids blocking Tomcat startup if RabbitMQ is down.
  private static volatile MessagePublisher publisher;

  // SERVER_ID identifies this instance in multi-server deployments (set via env var or default)
  private static final String SERVER_ID =
      System.getenv("SERVER_ID") != null ? System.getenv("SERVER_ID") : "server-1";

  @OnOpen
  public void onOpen(Session session, @PathParam("roomId") String roomId) {
    RoomManager.addSession(roomId, session);
    logger.info("Session opened: session=" + session.getId() + ", room=" + roomId);
  }

  @OnMessage
  public void onMessage(String message, Session session, @PathParam("roomId") String roomId) {
    logger.info("Message received: session=" + session.getId() + ", room=" + roomId);

    // 1. Deserialize
    ChatMessageDto chatMsg;
    try {
      chatMsg = ChatMessageDto.fromJson(message);
    } catch (Exception e) {
      sendError(session, null, "Invalid JSON format: " + e.getMessage());
      return;
    }

    // 2. Validate
    List<String> errors = chatMsg.validate();
    if (!errors.isEmpty()) {
      sendError(session, chatMsg.getMessageId(), String.join("; ", errors));
      return;
    }

    // 3. Publish to RabbitMQ (with publisher confirms)
    String clientIp = session.getUserProperties()
        .getOrDefault("javax.websocket.endpoint.remoteAddress", "unknown").toString();
    QueueMessage queueMsg = new QueueMessage(chatMsg, roomId, SERVER_ID, clientIp);

    boolean confirmed = getPublisher().publish(queueMsg);

    // 4. ACK back to sender — only after RabbitMQ confirms receipt
    if (confirmed) {
      sendAck(session, chatMsg.getMessageId());
    } else {
      sendError(session, chatMsg.getMessageId(), "Failed to publish message, please retry");
    }
  }

  @OnClose
  public void onClose(Session session, @PathParam("roomId") String roomId, CloseReason reason) {
    RoomManager.removeSession(roomId, session);
    logger.info("Session closed: session=" + session.getId() + ", room=" + roomId
        + ", reason=" + reason);
  }

  @OnError
  public void onError(Session session, Throwable exception) {
    logger.severe("Transport error: session=" + session.getId()
        + ", error=" + exception.getMessage());
  }

  // ── Helpers ──────────────────────────────────────────────────────────────

  private void sendAck(Session session, String messageId) {
    try {
      session.getBasicRemote().sendText(ChatResponse.ack(messageId).toJson());
    } catch (IOException e) {
      logger.warning("Failed to send ACK for messageId=" + messageId + ": " + e.getMessage());
    }
  }

  private void sendError(Session session, String messageId, String errorMsg) {
    try {
      ChatResponse errResp = messageId != null
          ? ChatResponse.error(messageId, errorMsg)
          : ChatResponse.error(errorMsg);
      session.getBasicRemote().sendText(errResp.toJson());
    } catch (IOException e) {
      logger.warning("Failed to send error response: " + e.getMessage());
    }
  }

  private MessagePublisher getPublisher() {
    if (publisher == null) {
      synchronized (ServerEndpoint.class) {
        if (publisher == null) {
          try {
            publisher = new MessagePublisher(ChannelPool.getInstance());
            logger.info("MessagePublisher initialized.");
          } catch (Exception e) {
            throw new RuntimeException("Failed to initialize RabbitMQ publisher", e);
          }
        }
      }
    }
    return publisher;
  }
}

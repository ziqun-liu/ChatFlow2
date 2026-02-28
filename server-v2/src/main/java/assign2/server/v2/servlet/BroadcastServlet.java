package assign2.server.v2.servlet;

import assign2.server.v2.model.ChatResponse;
import assign2.server.v2.model.QueueMessage;
import assign2.server.v2.room.RoomManager;
import com.google.gson.Gson;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.websocket.Session;

/**
 * HTTP endpoint called by the Consumer after it dequeues a message from RabbitMQ.
 *
 * Flow:
 *   Consumer → POST /broadcast (body: QueueMessage JSON)
 *       → BroadcastServlet deserializes roomId
 *       → looks up sessions in RoomManager.rooms
 *       → broadcasts to all open sessions in that room
 *       → returns 200 OK on success, 400/500 on failure
 *
 * Consumer only sends basicAck to RabbitMQ after receiving 200 from this endpoint,
 * ensuring at-least-once delivery.
 */
@WebServlet("/broadcast")
public class BroadcastServlet extends HttpServlet {

  private static final Logger logger = Logger.getLogger(BroadcastServlet.class.getName());
  private static final Gson GSON = new Gson();

  @Override
  protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {

    // 1. Read request body
    String body;
    try {
      body = new String(req.getInputStream().readAllBytes(), "UTF-8");
    } catch (IOException e) {
      logger.warning("Failed to read request body: " + e.getMessage());
      resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
      resp.getWriter().write("Failed to read body");
      return;
    }

    // 2. Deserialize QueueMessage
    QueueMessage queueMsg;
    try {
      queueMsg = GSON.fromJson(body, QueueMessage.class);
      if (queueMsg == null || queueMsg.getRoomId() == null || queueMsg.getMessageId() == null) {
        throw new IllegalArgumentException("Missing required fields");
      }
    } catch (Exception e) {
      logger.warning("Invalid broadcast payload: " + e.getMessage());
      resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
      resp.getWriter().write("Invalid payload: " + e.getMessage());
      return;
    }

    // 3. Look up sessions for this room
    String roomId = queueMsg.getRoomId();
    Set<Session> sessions = RoomManager.getSessions(roomId);

    if (sessions == null || sessions.isEmpty()) {
      // No clients connected to this room on this server instance — still a success.
      // Other server instances may have sessions for this room.
      logger.info("No sessions for room=" + roomId + ", nothing to broadcast.");
      resp.setStatus(HttpServletResponse.SC_OK);
      resp.getWriter().write("OK");
      return;
    }

    // 4. Broadcast to all open sessions in the room
    String broadcastJson = new ChatResponse(queueMsg).toJson();
    AtomicInteger sent = new AtomicInteger(0);
    AtomicInteger failed = new AtomicInteger(0);

    for (Session session : sessions) {
      synchronized (session) {
        if (session.isOpen()) {
          try {
            session.getBasicRemote().sendText(broadcastJson);
            sent.incrementAndGet();
          } catch (IOException e) {
            logger.warning("Failed to broadcast to session=" + session.getId()
                + ", room=" + roomId + ": " + e.getMessage());
            failed.incrementAndGet();
          }
        }
      }
    }

    logger.info("Broadcast complete: room=" + roomId + ", messageId=" + queueMsg.getMessageId()
        + ", sent=" + sent + ", failed=" + failed);

    // 5. Return 200 so Consumer knows to basicAck RabbitMQ.
    // Even partial failures (some sessions failed) return 200 —
    // failed sessions are dead connections and will be cleaned up on onClose.
    resp.setStatus(HttpServletResponse.SC_OK);
    resp.getWriter().write("OK");
  }
}

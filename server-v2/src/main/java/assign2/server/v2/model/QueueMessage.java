package assign2.server.v2.model;

import com.google.gson.Gson;

public class QueueMessage {

  private static final Gson GSON = new Gson();

  private final String messageId;
  private final String roomId;
  private final String userId;
  private final String username;
  private final String message;
  private final String timestamp;
  private final String messageType;
  private final String serverId;
  private final String clientIp;

  private QueueMessage(String messageId, String roomId, String userId, String username, String message,
      String timestamp, String messageType, String serverId, String clientIp) {
    this.messageId = messageId;
    this.roomId = roomId;
    this.userId = userId;
    this.username = username;
    this.message = message;
    this.timestamp = timestamp;
    this.messageType = messageType;
    this.serverId = serverId;
    this.clientIp = clientIp;
  }

  public static QueueMessage from(ChatMessageDto dto, String roomId, String serverId, String clientIp) {
    return new QueueMessage(
        dto.getMessageId(),
        roomId,
        dto.getUserId(),
        dto.getUsername(),
        dto.getMessage(),
        dto.getTimestamp(),
        dto.getMessageType(),
        serverId,
        clientIp
    );
  }

  public String toJson() {
    return GSON.toJson(this);
  }
}
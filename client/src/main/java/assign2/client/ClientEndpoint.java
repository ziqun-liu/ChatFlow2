package assign2.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

public class ClientEndpoint extends WebSocketClient {

  // messageId -> future waiting for its ACK
  private final ConcurrentHashMap<String, CompletableFuture<String>> pendingAcks = new ConcurrentHashMap<>();

  public ClientEndpoint(URI serverUri) {
    super(serverUri);
  }

  @Override
  public void onOpen(ServerHandshake handshakedata) {
  }

  /**
   * Routes incoming messages: - type=ACK: completes the future for the matching messageId -
   * anything else (BROADCAST, ERROR broadcast): ignored by client Error responses (type=ERROR) from
   * validation failures are also routed via messageId so the sender thread knows its message was
   * rejected.
   */
  @Override
  public void onMessage(String message) {
    try {
      JsonObject obj = JsonParser.parseString(message).getAsJsonObject();
      String type = obj.has("type") ? obj.get("type").getAsString() : null;
      if ("ACK".equals(type) || "ERROR".equals(type)) {
        String messageId = obj.has("messageId") ? obj.get("messageId").getAsString() : null;
        if (messageId != null) {
          CompletableFuture<String> future = this.pendingAcks.get(messageId);
          if (future != null) {
            future.complete(message);
          }
        }
      }
      // BROADCAST messages are ignored — client does not process incoming chat messages
    } catch (Exception e) {
      System.err.println("[ClientEndpoint] Failed to parse message: " + e.getMessage());
    }
  }

  /**
   * On close, fail all pending futures so SenderWorker threads are not blocked forever.
   */
  @Override
  public void onClose(int code, String reason, boolean remote) {
    failAllPending();
  }

  @Override
  public void onError(Exception ex) {
    System.err.println("[WS Error] " + ex.getMessage());
    failAllPending();
  }

  /**
   * Sends a message and waits for the server's ACK identified by messageId. Returns the raw ACK
   * JSON string, or null on timeout.
   */
  public String sendAndAwaitAck(String json, String messageId, long timeoutMs)
      throws InterruptedException {
    CompletableFuture<String> future = new CompletableFuture<>();
    this.pendingAcks.put(messageId, future);
    try {
      send(json);
      return future.get(timeoutMs, TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      return null;
    } catch (Exception e) {
      return null;
    } finally {
      this.pendingAcks.remove(messageId);
    }
  }

  private void failAllPending() {
    this.pendingAcks.values().forEach(f -> f.complete(null));
    this.pendingAcks.clear();
  }
}

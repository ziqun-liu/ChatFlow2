package assign2.consumer;

import assign2.consumer.model.QueueMessage;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.List;
import java.util.logging.Logger;

/**
 * Sends HTTP POST /broadcast to all known server-v2 instances.
 *
 * Each server instance holds its own subset of WebSocket sessions (sticky ALB),
 * so we must notify ALL servers — each will broadcast to whichever sessions it holds.
 *
 * Returns true only if ALL servers respond 200, so RoomConsumer can safely basicAck.
 * If any server fails, RoomConsumer will basicNack and RabbitMQ will re-deliver.
 */
public class ServerNotifier {

  private static final Logger logger = Logger.getLogger(ServerNotifier.class.getName());
  private static final Duration TIMEOUT = Duration.ofSeconds(5);

  private final List<String> serverUrls;
  private final HttpClient httpClient;

  public ServerNotifier(List<String> serverUrls) {
    this.serverUrls = serverUrls;
    this.httpClient = HttpClient.newBuilder()
        .connectTimeout(TIMEOUT)
        .build();
  }

  /**
   * POSTs the QueueMessage JSON to /broadcast on every server-v2 instance.
   *
   * @return true if all servers returned HTTP 200, false if any failed
   */
  public boolean notifyAll(QueueMessage msg) {
    String body = msg.toJson();
    boolean allSuccess = true;

    for (String baseUrl : serverUrls) {
      String url = baseUrl + "/broadcast";
      try {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(TIMEOUT)
            .header("Content-Type", "application/json")
            .POST(BodyPublishers.ofString(body))
            .build();

        HttpResponse<String> response = httpClient.send(request, BodyHandlers.ofString());

        if (response.statusCode() == 200) {
          logger.fine("Broadcast OK: url=" + url + ", messageId=" + msg.getMessageId());
        } else {
          logger.warning("Broadcast failed: url=" + url
              + ", status=" + response.statusCode()
              + ", messageId=" + msg.getMessageId());
          allSuccess = false;
        }

      } catch (IOException | InterruptedException e) {
        if (e instanceof InterruptedException) {
          Thread.currentThread().interrupt();
        }
        logger.warning("Broadcast error: url=" + url
            + ", messageId=" + msg.getMessageId()
            + ", error=" + e.getMessage());
        allSuccess = false;
      }
    }

    return allSuccess;
  }
}

package assign2.consumer;

import assign2.consumer.config.RabbitMQConfig;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Entry point for the Consumer service.
 *
 * Reads config.properties, constructs ServerNotifier and ConsumerPool,
 * starts consuming, and registers a JVM shutdown hook for graceful exit.
 *
 * Run: java -jar consumer.jar
 * Override config via env vars: RABBITMQ_HOST, SERVER_URLS, CONSUMER_THREADS
 */
public class ConsumerMain {

  private static final Logger logger = Logger.getLogger(ConsumerMain.class.getName());
  private static final String PROPERTIES_FILE = "config.properties";

  public static void main(String[] args) throws Exception {
    Properties props = loadProperties();

    // Server URLs — comma-separated, e.g. "http://server1:8080/server,http://server2:8080/server"
    String urlsRaw = resolve("SERVER_URLS", props, "server.urls", "http://localhost:8080/server");
    List<String> serverUrls = Arrays.stream(urlsRaw.split(","))
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .collect(Collectors.toList());

    // Number of consumer threads
    String threadsRaw = resolve("CONSUMER_THREADS", props, "consumer.threads", "4");
    int numThreads = Integer.parseInt(threadsRaw);

    logger.info("ConsumerMain starting: threads=" + numThreads
        + ", servers=" + serverUrls
        + ", rabbitmq=" + RabbitMQConfig.HOST + ":" + RabbitMQConfig.PORT);

    ServerNotifier notifier = new ServerNotifier(serverUrls);
    ConsumerPool pool = new ConsumerPool(numThreads, notifier);

    // Graceful shutdown on SIGTERM or Ctrl+C
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      logger.info("Shutdown signal received, stopping ConsumerPool...");
      pool.shutdown();
    }, "shutdown-hook"));

    pool.start();

    // Keep main thread alive — ConsumerPool threads do the work
    Thread.currentThread().join();
  }

  // ── Helpers ──────────────────────────────────────────────────────────────

  private static String resolve(String envKey, Properties props,
      String propKey, String defaultValue) {
    String envVal = System.getenv(envKey);
    if (envVal != null && !envVal.isEmpty()) return envVal;
    String propVal = props.getProperty(propKey);
    if (propVal != null && !propVal.isEmpty()) return propVal;
    return defaultValue;
  }

  private static Properties loadProperties() {
    Properties props = new Properties();
    try (InputStream is = ConsumerMain.class
        .getClassLoader().getResourceAsStream(PROPERTIES_FILE)) {
      if (is != null) {
        props.load(is);
      } else {
        logger.warning(PROPERTIES_FILE + " not found, using env vars or defaults.");
      }
    } catch (IOException e) {
      logger.warning("Failed to load " + PROPERTIES_FILE + ": " + e.getMessage());
    }
    return props;
  }
}

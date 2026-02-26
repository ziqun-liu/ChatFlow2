package assign2.consumer;

import assign2.consumer.config.RabbitMQConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Creates and manages a pool of RoomConsumer threads.
 * Distributes the 20 room queues evenly across all consumer threads.
 *
 * Example: 4 threads, 20 rooms
 *   thread-0 → room.1,  room.5,  room.9,  room.13, room.17
 *   thread-1 → room.2,  room.6,  room.10, room.14, room.18
 *   thread-2 → room.3,  room.7,  room.11, room.15, room.19
 *   thread-3 → room.4,  room.8,  room.12, room.16, room.20
 *
 * Round-robin distribution ensures even load even if some rooms are hotter than others.
 */
public class ConsumerPool {

  private static final Logger logger = Logger.getLogger(ConsumerPool.class.getName());

  private final int numThreads;
  private final ServerNotifier notifier;
  private final ExecutorService executor;
  private final List<RoomConsumer> consumers = new ArrayList<>();

  public ConsumerPool(int numThreads, ServerNotifier notifier) {
    this.numThreads = numThreads;
    this.notifier = notifier;
    this.executor = Executors.newFixedThreadPool(numThreads);
  }

  /**
   * Distributes rooms across threads (round-robin) and starts all RoomConsumer threads.
   */
  public void start() {
    // Build per-thread room lists via round-robin
    List<List<String>> roomsPerThread = new ArrayList<>();
    for (int i = 0; i < numThreads; i++) {
      roomsPerThread.add(new ArrayList<>());
    }

    for (int roomNum = 1; roomNum <= RabbitMQConfig.ROOM_COUNT; roomNum++) {
      String roomId = String.valueOf(roomNum);
      int threadIndex = (roomNum - 1) % numThreads;
      roomsPerThread.get(threadIndex).add(roomId);
    }

    // Start one RoomConsumer per thread
    for (int i = 0; i < numThreads; i++) {
      List<String> assignedRooms = roomsPerThread.get(i);
      RoomConsumer consumer = new RoomConsumer(assignedRooms, notifier);
      consumers.add(consumer);
      executor.submit(consumer);
      logger.info("Started consumer thread-" + i + " for rooms=" + assignedRooms);
    }

    logger.info("ConsumerPool started: " + numThreads + " threads, "
        + RabbitMQConfig.ROOM_COUNT + " rooms total.");
  }

  /**
   * Signals all consumers to stop and waits for clean shutdown.
   */
  public void shutdown() {
    logger.info("Shutting down ConsumerPool...");
    consumers.forEach(RoomConsumer::stop);
    executor.shutdown();
    try {
      if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
        executor.shutdownNow();
        logger.warning("ConsumerPool forced shutdown after timeout.");
      } else {
        logger.info("ConsumerPool shut down cleanly.");
      }
    } catch (InterruptedException e) {
      executor.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }
}

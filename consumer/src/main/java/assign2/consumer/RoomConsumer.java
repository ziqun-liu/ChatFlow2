package assign2.consumer;

import assign2.consumer.config.RabbitMQConfig;
import assign2.consumer.model.QueueMessage;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;
import java.io.IOException;
import java.util.List;
import java.util.logging.Logger;

/**
 * A Runnable that consumes messages from a set of RabbitMQ queues (one per room) and notifies all
 * server-v2 instances via HTTP POST /broadcast.
 * <p>
 * Delivery guarantee: - basicAck only after ServerNotifier.notifyAll() returns true (all servers
 * 200) - basicNack (requeue=true) if any server fails — message re-delivered by RabbitMQ
 * <p>
 * One RoomConsumer thread handles multiple rooms (queues), consuming from each in a push-based
 * model (basicConsume). The thread blocks on the RabbitMQ connection until shutdown is requested.
 */
public class RoomConsumer implements Runnable {

  private static final Logger logger = Logger.getLogger(RoomConsumer.class.getName());
  private static final int PREFETCH_COUNT = 10;

  private final List<String> roomIds;
  private final ServerNotifier notifier;
  private volatile boolean running = true;

  public RoomConsumer(List<String> roomIds, ServerNotifier notifier) {
    this.roomIds = roomIds;
    this.notifier = notifier;
  }

  @Override
  public void run() {
    ConnectionFactory factory = new ConnectionFactory();
    factory.setHost(RabbitMQConfig.HOST);
    factory.setPort(RabbitMQConfig.PORT);
    factory.setUsername(RabbitMQConfig.USERNAME);
    factory.setPassword(RabbitMQConfig.PASSWORD);
    factory.setAutomaticRecoveryEnabled(true);  // auto-reconnect on network failure

    try (Connection connection = factory.newConnection(
        "consumer-" + roomIds); Channel channel = connection.createChannel()) {

      // Limit unacknowledged messages in-flight per consumer thread
      channel.basicQos(PREFETCH_COUNT);

      // Declare queues and bind to exchange for each assigned room
      for (String roomId : this.roomIds) {
        String queueName = RabbitMQConfig.queueName(roomId);
        String routingKey = RabbitMQConfig.routingKey(roomId);

        // Queues and bindings are pre-configured by rabbitmq-setup.sh.
        // Use passive declare to verify existence without modifying parameters.
        channel.queueDeclarePassive(queueName);

        // Register push-based consumer — autoAck=false for manual ack
        channel.basicConsume(queueName, false, new DefaultConsumer(channel) {
          @Override
          public void handleDelivery(String consumerTag, Envelope envelope,
              AMQP.BasicProperties properties, byte[] body) throws IOException {

            long deliveryTag = envelope.getDeliveryTag();
            String json = new String(body, "UTF-8");

            QueueMessage msg;
            try {
              msg = QueueMessage.fromJson(json);
            } catch (Exception e) {
              // Malformed message — ack to discard, avoid infinite requeue loop
              logger.warning("Malformed message, discarding: " + e.getMessage());
              channel.basicAck(deliveryTag, false);
              return;
            }

            logger.fine("Consumed: messageId=" + msg.getMessageId() + ", room=" + msg.getRoomId());

            // Notify all server-v2 instances — ack only on full success
            boolean success = notifier.notifyAll(msg);
            if (success) {
              channel.basicAck(deliveryTag, false);
            } else if (envelope.isRedeliver()) {
              // Already retried once — discard to avoid infinite requeue loop.
              // In production, configure a dead letter exchange to capture these.
              logger.warning("Discarding redelivered messageId=" + msg.getMessageId()
                  + " after failed retry.");
              channel.basicNack(deliveryTag, false, false); // requeue=false
            } else {
              // First failure — allow one requeue retry
              channel.basicNack(deliveryTag, false, true);  // requeue=true
              logger.warning("Nacked messageId=" + msg.getMessageId() + ", will be requeued once.");
            }
          }
        });

        logger.info("Consuming queue=" + queueName + " (room=" + roomId + ")");
      }

      // Block until shutdown signal
      while (running && connection.isOpen()) {
        Thread.sleep(500);
      }

    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      logger.info("RoomConsumer interrupted for rooms=" + roomIds);
    } catch (Exception e) {
      logger.log(java.util.logging.Level.SEVERE, "RoomConsumer error for rooms=" + roomIds, e);
    }
  }

  public void stop() {
    running = false;
  }
}

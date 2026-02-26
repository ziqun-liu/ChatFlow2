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
 * A Runnable that consumes messages from a set of RabbitMQ queues (one per room)
 * and notifies all server-v2 instances via HTTP POST /broadcast.
 *
 * Delivery guarantee:
 *   - basicAck only after ServerNotifier.notifyAll() returns true (all servers 200)
 *   - basicNack (requeue=true) if any server fails — message re-delivered by RabbitMQ
 *
 * One RoomConsumer thread handles multiple rooms (queues), consuming from each
 * in a push-based model (basicConsume). The thread blocks on the RabbitMQ
 * connection until shutdown is requested.
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

    try (Connection connection = factory.newConnection("consumer-" + roomIds);
         Channel channel = connection.createChannel()) {

      // Limit unacknowledged messages in-flight per consumer thread
      channel.basicQos(PREFETCH_COUNT);

      // Declare queues and bind to exchange for each assigned room
      for (String roomId : roomIds) {
        String queueName = RabbitMQConfig.queueName(roomId);
        String routingKey = RabbitMQConfig.routingKey(roomId);

        // durable=true, exclusive=false, autoDelete=false
        channel.queueDeclare(queueName, true, false, false, null);
        channel.queueBind(queueName, RabbitMQConfig.EXCHANGE, routingKey);

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

            logger.fine("Consumed: messageId=" + msg.getMessageId()
                + ", room=" + msg.getRoomId());

            // Notify all server-v2 instances — ack only on full success
            boolean success = notifier.notifyAll(msg);
            if (success) {
              channel.basicAck(deliveryTag, false);
            } else {
              // requeue=true: RabbitMQ will re-deliver to this or another consumer
              channel.basicNack(deliveryTag, false, true);
              logger.warning("Nacked messageId=" + msg.getMessageId()
                  + ", will be requeued.");
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
      logger.severe("RoomConsumer error for rooms=" + roomIds + ": " + e.getMessage());
    }
  }

  public void stop() {
    running = false;
  }
}

package assign2.server.v2.rabbitmq;

import assign2.server.v2.config.RabbitMQConfig;
import assign2.server.v2.model.QueueMessage;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import java.util.logging.Logger;

/**
 * Publishes a QueueMessage to RabbitMQ using publisher confirms.
 * Borrows a channel from ChannelPool, publishes, waits for confirm, then returns channel.
 *
 * Returns true if RabbitMQ confirmed receipt, false otherwise.
 */
public class MessagePublisher {

  private static final Logger logger = Logger.getLogger(MessagePublisher.class.getName());
  private static final long CONFIRM_TIMEOUT_MS = 3000;

  private final ChannelPool channelPool;

  public MessagePublisher(ChannelPool channelPool) {
    this.channelPool = channelPool;
  }

  /**
   * Publishes message to chat.exchange with routing key room.{roomId}.
   * Blocks until RabbitMQ confirms (or times out).
   *
   * @return true if confirmed by RabbitMQ, false on timeout or error
   */
  public boolean publish(QueueMessage queueMsg) {
    Channel channel = null;
    try {
      channel = channelPool.borrow();

      String routingKey = RabbitMQConfig.routingKey(queueMsg.getRoomId());
      byte[] body = queueMsg.toJson().getBytes("UTF-8");

      AMQP.BasicProperties props = new AMQP.BasicProperties.Builder()
          .contentType("application/json")
          .deliveryMode(2)           // persistent
          .messageId(queueMsg.getMessageId())
          .build();

      channel.basicPublish(RabbitMQConfig.EXCHANGE, routingKey, props, body);

      // Wait for RabbitMQ broker confirm — blocks up to CONFIRM_TIMEOUT_MS
      boolean confirmed = channel.waitForConfirms(CONFIRM_TIMEOUT_MS);
      if (!confirmed) {
        logger.warning("RabbitMQ nack for messageId=" + queueMsg.getMessageId());
      }
      return confirmed;

    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      logger.warning("Interrupted while waiting for confirm: " + e.getMessage());
      return false;
    } catch (Exception e) {
      logger.severe("Failed to publish messageId=" + queueMsg.getMessageId()
          + ": " + e.getMessage());
      return false;
    } finally {
      channelPool.returnChannel(channel);
    }
  }
}

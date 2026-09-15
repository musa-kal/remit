package ca.musakaleem.remit.api.deadletter;

import ca.musakaleem.remit.api.web.ServiceUnavailableException;
import ca.musakaleem.remit.common.Topics;
import ca.musakaleem.remit.common.event.EventHeaders;
import ca.musakaleem.remit.common.event.EventType;
import ca.musakaleem.remit.common.event.PaymentInstruction;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Republishes an instruction to {@code payments.instructions} exactly as intake-service publishes
 * one (docs/decisions/0003-dlt-and-replay.md): key = debtor account, JSON with a {@code __TypeId__}
 * header, and the five standard headers with {@code eventType=PAYMENT_INSTRUCTION} and {@code
 * correlationId=batchId}. It waits for the broker's acknowledgement.
 */
@Component
public class ReplayPublisher {

  private final KafkaTemplate<String, Object> kafkaTemplate;
  private final Clock clock;
  private final Duration timeout;

  /**
   * Creates the publisher.
   *
   * @param kafkaTemplate template configured with acks=all and idempotence
   * @param clock clock for the occurredAt header
   * @param properties provides the acknowledgement timeout
   */
  public ReplayPublisher(
      KafkaTemplate<String, Object> kafkaTemplate, Clock clock, ReplayProperties properties) {
    this.kafkaTemplate = kafkaTemplate;
    this.clock = clock;
    this.timeout = properties.publishTimeout();
  }

  /**
   * Publishes the instruction and waits for the acknowledgement.
   *
   * @param instruction the instruction with its new replay count
   * @throws ServiceUnavailableException when Kafka does not acknowledge in time
   */
  public void publish(PaymentInstruction instruction) {
    ProducerRecord<String, Object> record =
        EventHeaders.record(
            Topics.INSTRUCTIONS,
            instruction.debtor().account(),
            instruction,
            EventType.PAYMENT_INSTRUCTION,
            instruction.batchId().toString(),
            clock.instant());
    try {
      kafkaTemplate.send(record).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ServiceUnavailableException("Interrupted while publishing the replay", e);
    } catch (ExecutionException | TimeoutException e) {
      throw new ServiceUnavailableException(
          "Kafka did not acknowledge the replay; nothing was changed, try again", e);
    }
  }
}

package ca.musakaleem.remit.routing.publish;

import ca.musakaleem.remit.common.Topics;
import ca.musakaleem.remit.common.event.EventHeaders;
import ca.musakaleem.remit.common.event.EventType;
import ca.musakaleem.remit.common.event.InstructionStatus;
import ca.musakaleem.remit.common.event.InstructionStatusEvent;
import ca.musakaleem.remit.common.event.PaymentInstruction;
import ca.musakaleem.remit.routing.config.RoutingProperties;
import ca.musakaleem.remit.routing.error.RoutingException;
import ca.musakaleem.remit.routing.rules.RoutingDecision;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes routed instructions and status events, both keyed by idempotency key and carrying the
 * five standard headers (spec section 5). Every send waits for the broker's acknowledgement ({@code
 * send(...).get(timeout)}), so a failed publish surfaces as a retryable {@link RoutingException}
 * before the ledger row is updated.
 */
@Component
public class RoutingEventPublisher {

  /** Extra header on routed records: ROUTED or REQUIRES_REVIEW, for downstream consumers. */
  public static final String ROUTING_STATUS_HEADER = "routingStatus";

  private final KafkaTemplate<String, Object> kafkaTemplate;
  private final Clock clock;
  private final Duration timeout;

  /**
   * Creates the publisher.
   *
   * @param kafkaTemplate template configured with acks=all and idempotence
   * @param clock clock for event times
   * @param properties provides the acknowledgement timeout
   */
  public RoutingEventPublisher(
      KafkaTemplate<String, Object> kafkaTemplate, Clock clock, RoutingProperties properties) {
    this.kafkaTemplate = kafkaTemplate;
    this.clock = clock;
    this.timeout = properties.publishTimeout();
  }

  /**
   * Publishes the instruction to its {@code payments.routed.<channel>} topic and waits for the
   * acknowledgement.
   *
   * @param instruction the instruction
   * @param decision where it goes and with which outcome
   * @throws RoutingException when Kafka does not acknowledge in time
   */
  public void publishRouted(PaymentInstruction instruction, RoutingDecision decision) {
    ProducerRecord<String, Object> record =
        EventHeaders.record(
            decision.topic(),
            instruction.idempotencyKey(),
            instruction,
            EventType.INSTRUCTION_ROUTED,
            instruction.batchId().toString(),
            clock.instant());
    record.headers().add(EventHeaders.header(ROUTING_STATUS_HEADER, decision.outcome().name()));
    await(kafkaTemplate.send(record), "routed instruction to " + decision.topic());
  }

  /**
   * Publishes an {@link InstructionStatusEvent} to {@code payments.status-events} and waits for the
   * acknowledgement.
   *
   * @param instruction the instruction the event is about
   * @param status new status
   * @param detail optional human readable detail, may be {@code null}
   * @throws RoutingException when Kafka does not acknowledge in time
   */
  public void publishStatus(
      PaymentInstruction instruction, InstructionStatus status, String detail) {
    Instant now = clock.instant();
    InstructionStatusEvent event =
        new InstructionStatusEvent(
            instruction.idempotencyKey(),
            instruction.batchId(),
            status,
            instruction.channel(),
            detail,
            now);
    ProducerRecord<String, Object> record =
        EventHeaders.record(
            Topics.STATUS_EVENTS,
            instruction.idempotencyKey(),
            event,
            EventType.INSTRUCTION_STATUS,
            instruction.batchId().toString(),
            now);
    await(kafkaTemplate.send(record), status + " status event");
  }

  private void await(CompletableFuture<?> future, String what) {
    try {
      future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RoutingException("Interrupted while publishing " + what, e);
    } catch (ExecutionException e) {
      throw new RoutingException("Kafka rejected " + what, e.getCause());
    } catch (TimeoutException e) {
      throw new RoutingException("Timed out after " + timeout + " publishing " + what, e);
    }
  }
}

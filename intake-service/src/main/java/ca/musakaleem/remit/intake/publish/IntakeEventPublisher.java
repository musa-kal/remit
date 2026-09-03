package ca.musakaleem.remit.intake.publish;

import ca.musakaleem.remit.common.Topics;
import ca.musakaleem.remit.common.event.BatchEvent;
import ca.musakaleem.remit.common.event.BatchStatus;
import ca.musakaleem.remit.common.event.EventHeaders;
import ca.musakaleem.remit.common.event.EventType;
import ca.musakaleem.remit.common.event.InstructionRejected;
import ca.musakaleem.remit.common.event.PaymentInstruction;
import ca.musakaleem.remit.intake.config.IntakeProperties;
import ca.musakaleem.remit.intake.validation.ReasonCode;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

/**
 * Publishes intake events with the standard headers (spec section 5). Instructions are keyed by
 * debtor account (FR-INT-05); rejections and batch events by batch id.
 */
@Component
public class IntakeEventPublisher {

  private static final Logger log = LoggerFactory.getLogger(IntakeEventPublisher.class);

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
  public IntakeEventPublisher(
      KafkaTemplate<String, Object> kafkaTemplate, Clock clock, IntakeProperties properties) {
    this.kafkaTemplate = kafkaTemplate;
    this.clock = clock;
    this.timeout = properties.publishTimeout();
  }

  /**
   * Sends an instruction without waiting; callers wait for a whole chunk with {@link #awaitAll}.
   *
   * @param instruction the validated instruction
   * @return future completed when Kafka acknowledges the record
   */
  public CompletableFuture<SendResult<String, Object>> publishInstruction(
      PaymentInstruction instruction) {
    ProducerRecord<String, Object> record =
        EventHeaders.record(
            Topics.INSTRUCTIONS,
            instruction.debtor().account(),
            instruction,
            EventType.PAYMENT_INSTRUCTION,
            instruction.batchId().toString(),
            clock.instant());
    return kafkaTemplate.send(record);
  }

  /**
   * Publishes a rejected row and waits for the acknowledgement.
   *
   * @param rejection the rejection event, raw line already masked
   */
  public void publishRejection(InstructionRejected rejection) {
    ProducerRecord<String, Object> record =
        EventHeaders.record(
            Topics.REJECTED,
            rejection.batchId().toString(),
            rejection,
            EventType.INSTRUCTION_REJECTED,
            rejection.batchId().toString(),
            clock.instant());
    await(kafkaTemplate.send(record), "rejection of line " + rejection.lineNumber());
  }

  /**
   * Publishes a batch lifecycle event and waits for the acknowledgement.
   *
   * @param event the event
   * @param reason file-level or batch-level reason for a FAILED batch, or {@code null}
   */
  public void publishBatchEvent(BatchEvent event, ReasonCode reason) {
    EventType type =
        event.status() == BatchStatus.RECEIVED
            ? EventType.BATCH_RECEIVED
            : EventType.BATCH_COMPLETED;
    ProducerRecord<String, Object> record =
        EventHeaders.record(
            Topics.BATCH_EVENTS,
            event.batchId().toString(),
            event,
            type,
            event.batchId().toString(),
            event.occurredAt());
    if (reason != null) {
      record.headers().add(EventHeaders.header(EventHeaders.REASON_CODE, reason.name()));
    }
    await(kafkaTemplate.send(record), "batch event " + event.status());
    log.info(
        "Published {} for file {} (total={}, accepted={}, rejected={}, reason={})",
        event.status(),
        event.fileName(),
        event.totalRows(),
        event.acceptedRows(),
        event.rejectedRows(),
        reason);
  }

  /**
   * Waits until every send has been acknowledged.
   *
   * @param futures pending sends
   * @throws EventPublishException when a send fails or the timeout passes
   */
  public void awaitAll(List<? extends CompletableFuture<?>> futures) {
    await(
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)),
        futures.size() + " instructions");
  }

  private void await(CompletableFuture<?> future, String what) {
    try {
      future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new EventPublishException("Interrupted while publishing " + what, e);
    } catch (ExecutionException e) {
      throw new EventPublishException("Kafka rejected " + what, e.getCause());
    } catch (TimeoutException e) {
      throw new EventPublishException("Timed out after " + timeout + " publishing " + what, e);
    }
  }
}

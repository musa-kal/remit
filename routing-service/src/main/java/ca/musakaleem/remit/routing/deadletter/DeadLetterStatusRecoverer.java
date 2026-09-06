package ca.musakaleem.remit.routing.deadletter;

import ca.musakaleem.remit.common.event.InstructionStatus;
import ca.musakaleem.remit.common.event.PaymentInstruction;
import ca.musakaleem.remit.routing.logging.MdcKeys;
import ca.musakaleem.remit.routing.publish.RoutingEventPublisher;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.kafka.listener.ConsumerAwareRecordRecoverer;

/**
 * Decorates the dead-letter publisher: after a record is written to the DLT, publishes {@code
 * InstructionStatusEvent(DEAD_LETTERED)} with the failure reason as detail, so api-service can
 * update its read model. A poison pill has no readable instruction, hence no idempotency key, and
 * gets only its DLT record.
 */
public class DeadLetterStatusRecoverer implements ConsumerAwareRecordRecoverer {

  /** Longest detail text put on a status event; longer exception messages are cut. */
  static final int MAX_DETAIL_LENGTH = 500;

  private static final Logger log = LoggerFactory.getLogger(DeadLetterStatusRecoverer.class);

  private final ConsumerAwareRecordRecoverer deadLetterPublisher;
  private final RoutingEventPublisher publisher;

  /**
   * Creates the recoverer.
   *
   * @param deadLetterPublisher Spring Kafka's DeadLetterPublishingRecoverer
   * @param publisher status event publisher
   */
  public DeadLetterStatusRecoverer(
      ConsumerAwareRecordRecoverer deadLetterPublisher, RoutingEventPublisher publisher) {
    this.deadLetterPublisher = deadLetterPublisher;
    this.publisher = publisher;
  }

  @Override
  public void accept(ConsumerRecord<?, ?> record, Consumer<?, ?> consumer, Exception exception) {
    deadLetterPublisher.accept(record, consumer, exception);

    String detail = detail(exception);
    if (record.value() instanceof PaymentInstruction instruction
        && instruction.idempotencyKey() != null
        && instruction.batchId() != null) {
      MDC.put(MdcKeys.CORRELATION_ID, instruction.batchId().toString());
      MDC.put(MdcKeys.IDEMPOTENCY_KEY, instruction.idempotencyKey());
      try {
        publisher.publishStatus(instruction, InstructionStatus.DEAD_LETTERED, detail);
        log.warn("Instruction dead-lettered: {}", detail);
      } finally {
        MDC.remove(MdcKeys.CORRELATION_ID);
        MDC.remove(MdcKeys.IDEMPOTENCY_KEY);
      }
    } else {
      log.warn(
          "Unreadable record {}-{}@{} dead-lettered without a status event: {}",
          record.topic(),
          record.partition(),
          record.offset(),
          detail);
    }
  }

  /**
   * The message of the most specific cause, which is the routing failure rather than the listener
   * wrapper around it.
   *
   * @param exception the exception the error handler received
   * @return a message of at most {@value #MAX_DETAIL_LENGTH} characters
   */
  static String detail(Exception exception) {
    Throwable cause = NestedExceptionUtils.getMostSpecificCause(exception);
    String message = cause.getMessage() == null ? cause.getClass().getName() : cause.getMessage();
    return message.length() <= MAX_DETAIL_LENGTH
        ? message
        : message.substring(0, MAX_DETAIL_LENGTH - 3) + "...";
  }
}

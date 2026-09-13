package ca.musakaleem.remit.api.deadletter;

import ca.musakaleem.remit.api.audit.AuditEntityType;
import ca.musakaleem.remit.api.audit.AuditEventType;
import ca.musakaleem.remit.api.audit.AuditWriter;
import ca.musakaleem.remit.api.config.KafkaConfig;
import ca.musakaleem.remit.api.logging.MdcKeys;
import ca.musakaleem.remit.common.Topics;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code payments.instructions.DLT} as raw bytes: stores or updates the dead letter, marks
 * a readable instruction DEAD_LETTERED, then writes one DEAD_LETTER_RECORDED audit document.
 */
@Component
public class DeadLetterListener {

  /** Listener container id. */
  public static final String LISTENER_ID = "dead-letters";

  private static final Logger log = LoggerFactory.getLogger(DeadLetterListener.class);

  private final DeadLetterRecordParser parser;
  private final DeadLetterProjection projection;
  private final AuditWriter audit;

  /**
   * Creates the listener.
   *
   * @param parser DLT record parser
   * @param projection dead letter read model
   * @param audit audit trail
   */
  public DeadLetterListener(
      DeadLetterRecordParser parser, DeadLetterProjection projection, AuditWriter audit) {
    this.parser = parser;
    this.projection = projection;
    this.audit = audit;
  }

  /**
   * Handles one DLT record.
   *
   * @param record the consumed record, value as bytes
   */
  @KafkaListener(
      id = LISTENER_ID,
      idIsGroup = false,
      topics = Topics.INSTRUCTIONS_DLT,
      containerFactory = KafkaConfig.DLT_CONTAINER_FACTORY)
  public void onDeadLetter(ConsumerRecord<String, byte[]> record) {
    DeadLetterRecord parsed = parser.parse(record);
    String key = parsed.readable() ? parsed.instruction().idempotencyKey() : null;
    String batchId =
        parsed.readable() ? parsed.instruction().batchId().toString() : parsed.correlationId();
    if (batchId != null) {
      MDC.put(MdcKeys.CORRELATION_ID, batchId);
    }
    if (key != null) {
      MDC.put(MdcKeys.IDEMPOTENCY_KEY, key);
    }
    try {
      DeadLetterOutcome outcome = projection.record(parsed);
      DeadLetter deadLetter = outcome.deadLetter();
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("deadLetterId", deadLetter.getId());
      details.put("outcome", outcome.kind());
      details.put("deadLetterStatus", deadLetter.getStatus());
      details.put("readable", parsed.readable());
      details.put("originalTopic", parsed.originalTopic());
      details.put("originalPartition", parsed.originalPartition());
      details.put("originalOffset", parsed.originalOffset());
      details.put("exceptionClass", parsed.exceptionClass());
      details.put("exceptionMessage", parsed.exceptionMessage());
      details.put("replayCount", deadLetter.getReplayCount());
      audit.recordEvent(
          parsed.auditEventId(),
          AuditEventType.DEAD_LETTER_RECORDED,
          key != null ? AuditEntityType.INSTRUCTION : AuditEntityType.DEAD_LETTER,
          key != null ? key : String.valueOf(deadLetter.getId()),
          parsed.deadLetteredAt(),
          batchId,
          details);
      log.info(
          "Dead letter {} from {}-{}@{}: {} ({})",
          deadLetter.getId(),
          parsed.originalTopic(),
          parsed.originalPartition(),
          parsed.originalOffset(),
          outcome.kind(),
          parsed.exceptionClass());
    } finally {
      MDC.remove(MdcKeys.CORRELATION_ID);
      MDC.remove(MdcKeys.IDEMPOTENCY_KEY);
    }
  }
}

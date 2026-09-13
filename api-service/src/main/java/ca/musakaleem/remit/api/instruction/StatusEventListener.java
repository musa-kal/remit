package ca.musakaleem.remit.api.instruction;

import ca.musakaleem.remit.api.audit.AuditEntityType;
import ca.musakaleem.remit.api.audit.AuditEventType;
import ca.musakaleem.remit.api.audit.AuditWriter;
import ca.musakaleem.remit.api.instruction.InstructionProjection.StatusOutcome;
import ca.musakaleem.remit.api.logging.MdcKeys;
import ca.musakaleem.remit.api.messaging.EventMetadata;
import ca.musakaleem.remit.common.Topics;
import ca.musakaleem.remit.common.event.InstructionStatus;
import ca.musakaleem.remit.common.event.InstructionStatusEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code payments.status-events}: updates the instruction, its history and a dead letter
 * waiting for its replay, then writes one INSTRUCTION_DEAD_LETTERED or INSTRUCTION_STATUS_CHANGED
 * audit document.
 */
@Component
public class StatusEventListener {

  /** Listener container id. */
  public static final String LISTENER_ID = "status-events";

  private static final Logger log = LoggerFactory.getLogger(StatusEventListener.class);

  private final InstructionProjection projection;
  private final AuditWriter audit;

  /**
   * Creates the listener.
   *
   * @param projection instruction read model
   * @param audit audit trail
   */
  public StatusEventListener(InstructionProjection projection, AuditWriter audit) {
    this.projection = projection;
    this.audit = audit;
  }

  /**
   * Handles one status event.
   *
   * @param record the consumed record
   */
  @KafkaListener(id = LISTENER_ID, idIsGroup = false, topics = Topics.STATUS_EVENTS)
  public void onStatusEvent(ConsumerRecord<String, Object> record) {
    EventMetadata meta = EventMetadata.of(record);
    InstructionStatusEvent event = EventMetadata.payload(record, InstructionStatusEvent.class);
    if (event.occurredAt() == null) {
      event =
          new InstructionStatusEvent(
              event.idempotencyKey(),
              event.batchId(),
              event.status(),
              event.channel(),
              event.detail(),
              meta.occurredAt());
    }
    String batchId = event.batchId() == null ? meta.correlationId() : event.batchId().toString();
    putMdc(batchId, event.idempotencyKey());
    try {
      StatusOutcome outcome = projection.applyStatus(meta.eventId(), event);
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("status", event.status());
      details.put("channel", event.channel());
      details.put("detail", event.detail());
      details.put("batchId", batchId);
      details.put("readModelUpdated", outcome.statusApplied());
      if (!outcome.deadLettersUpdated().isEmpty()) {
        details.put("deadLettersUpdated", outcome.deadLettersUpdated());
      }
      audit.recordEvent(
          meta.eventId(),
          event.status() == InstructionStatus.DEAD_LETTERED
              ? AuditEventType.INSTRUCTION_DEAD_LETTERED
              : AuditEventType.INSTRUCTION_STATUS_CHANGED,
          AuditEntityType.INSTRUCTION,
          event.idempotencyKey(),
          event.occurredAt(),
          batchId,
          details);
      log.info(
          "Instruction status {}{}",
          event.status(),
          outcome.statusApplied() ? "" : " kept in history only");
    } finally {
      MDC.remove(MdcKeys.CORRELATION_ID);
      MDC.remove(MdcKeys.IDEMPOTENCY_KEY);
    }
  }

  private static void putMdc(String batchId, String idempotencyKey) {
    if (batchId != null) {
      MDC.put(MdcKeys.CORRELATION_ID, batchId);
    }
    if (idempotencyKey != null) {
      MDC.put(MdcKeys.IDEMPOTENCY_KEY, idempotencyKey);
    }
  }
}

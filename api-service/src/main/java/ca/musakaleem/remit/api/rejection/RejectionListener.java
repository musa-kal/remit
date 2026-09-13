package ca.musakaleem.remit.api.rejection;

import ca.musakaleem.remit.api.audit.AuditEntityType;
import ca.musakaleem.remit.api.audit.AuditEventType;
import ca.musakaleem.remit.api.audit.AuditWriter;
import ca.musakaleem.remit.api.logging.MdcKeys;
import ca.musakaleem.remit.api.messaging.EventMetadata;
import ca.musakaleem.remit.common.Topics;
import ca.musakaleem.remit.common.event.InstructionRejected;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code payments.rejected}: stores the rejected row, then writes one INSTRUCTION_REJECTED
 * audit document about its batch.
 */
@Component
public class RejectionListener {

  /** Listener container id. */
  public static final String LISTENER_ID = "rejections";

  private final RejectionProjection projection;
  private final AuditWriter audit;

  /**
   * Creates the listener.
   *
   * @param projection rejection read model
   * @param audit audit trail
   */
  public RejectionListener(RejectionProjection projection, AuditWriter audit) {
    this.projection = projection;
    this.audit = audit;
  }

  /**
   * Handles one rejected row.
   *
   * @param record the consumed record
   */
  @KafkaListener(id = LISTENER_ID, idIsGroup = false, topics = Topics.REJECTED)
  public void onRejected(ConsumerRecord<String, Object> record) {
    EventMetadata meta = EventMetadata.of(record);
    InstructionRejected event = EventMetadata.payload(record, InstructionRejected.class);
    String batchId = event.batchId().toString();
    MDC.put(MdcKeys.CORRELATION_ID, batchId);
    try {
      boolean stored = projection.apply(event, meta.occurredAt());
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("lineNumber", event.lineNumber());
      details.put("instructionRef", event.instructionRef());
      details.put("reasonCodes", event.reasonCodes());
      details.put("rawLine", event.rawLine());
      details.put("readModelUpdated", stored);
      audit.recordEvent(
          meta.eventId(),
          AuditEventType.INSTRUCTION_REJECTED,
          AuditEntityType.BATCH,
          batchId,
          meta.occurredAt(),
          batchId,
          details);
    } finally {
      MDC.remove(MdcKeys.CORRELATION_ID);
    }
  }
}

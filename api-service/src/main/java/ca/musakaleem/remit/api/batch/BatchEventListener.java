package ca.musakaleem.remit.api.batch;

import ca.musakaleem.remit.api.audit.AuditEntityType;
import ca.musakaleem.remit.api.audit.AuditEventType;
import ca.musakaleem.remit.api.audit.AuditWriter;
import ca.musakaleem.remit.api.logging.MdcKeys;
import ca.musakaleem.remit.api.messaging.EventMetadata;
import ca.musakaleem.remit.common.Topics;
import ca.musakaleem.remit.common.event.BatchEvent;
import ca.musakaleem.remit.common.event.BatchStatus;
import ca.musakaleem.remit.common.event.EventHeaders;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code payments.batch-events}: upserts the batch, then writes one BATCH_RECEIVED or
 * BATCH_COMPLETED audit document (FR-API-01, FR-API-02).
 */
@Component
public class BatchEventListener {

  /** Listener container id. */
  public static final String LISTENER_ID = "batch-events";

  private static final Logger log = LoggerFactory.getLogger(BatchEventListener.class);

  private final BatchProjection projection;
  private final AuditWriter audit;

  /**
   * Creates the listener.
   *
   * @param projection batch read model
   * @param audit audit trail
   */
  public BatchEventListener(BatchProjection projection, AuditWriter audit) {
    this.projection = projection;
    this.audit = audit;
  }

  /**
   * Handles one batch event.
   *
   * @param record the consumed record
   */
  @KafkaListener(id = LISTENER_ID, idIsGroup = false, topics = Topics.BATCH_EVENTS)
  public void onBatchEvent(ConsumerRecord<String, Object> record) {
    EventMetadata meta = EventMetadata.of(record);
    BatchEvent event = withOccurredAt(EventMetadata.payload(record, BatchEvent.class), meta);
    String reasonCode =
        EventHeaders.lastValue(record.headers(), EventHeaders.REASON_CODE).orElse(null);
    String batchId = event.batchId().toString();
    MDC.put(MdcKeys.CORRELATION_ID, batchId);
    try {
      boolean applied = projection.apply(event, reasonCode);
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("status", event.status());
      details.put("clientId", event.clientId());
      details.put("fileName", event.fileName());
      details.put("totalRows", event.totalRows());
      details.put("acceptedRows", event.acceptedRows());
      details.put("rejectedRows", event.rejectedRows());
      details.put("controlTotal", event.controlTotal());
      details.put("reasonCode", reasonCode);
      details.put("readModelUpdated", applied);
      audit.recordEvent(
          meta.eventId(),
          event.status() == BatchStatus.RECEIVED
              ? AuditEventType.BATCH_RECEIVED
              : AuditEventType.BATCH_COMPLETED,
          AuditEntityType.BATCH,
          batchId,
          event.occurredAt(),
          batchId,
          details);
      log.info(
          "Batch {} {} ({}){}",
          event.fileName(),
          event.status(),
          reasonCode == null ? "no reason" : reasonCode,
          applied ? "" : "; older than the stored state, ignored");
    } finally {
      MDC.remove(MdcKeys.CORRELATION_ID);
    }
  }

  private static BatchEvent withOccurredAt(BatchEvent event, EventMetadata meta) {
    if (event.occurredAt() != null) {
      return event;
    }
    return new BatchEvent(
        event.batchId(),
        event.clientId(),
        event.fileName(),
        event.fileHash(),
        event.status(),
        event.totalRows(),
        event.acceptedRows(),
        event.rejectedRows(),
        event.controlTotal(),
        meta.occurredAt());
  }
}

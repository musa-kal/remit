package ca.musakaleem.remit.api.instruction;

import ca.musakaleem.remit.api.audit.AuditEntityType;
import ca.musakaleem.remit.api.audit.AuditEventType;
import ca.musakaleem.remit.api.audit.AuditWriter;
import ca.musakaleem.remit.api.logging.MdcKeys;
import ca.musakaleem.remit.api.messaging.EventMetadata;
import ca.musakaleem.remit.common.Topics;
import ca.musakaleem.remit.common.event.EventHeaders;
import ca.musakaleem.remit.common.event.InstructionStatus;
import ca.musakaleem.remit.common.event.PaymentInstruction;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes the three {@code payments.routed.*} topics, the only source of the payment details
 * (parties, amount, value date) of an instruction that was routed, then writes one
 * INSTRUCTION_ROUTED audit document (docs/decisions/0009-audit-event-types.md).
 */
@Component
public class RoutedInstructionListener {

  /** Listener container id. */
  public static final String LISTENER_ID = "routed-instructions";

  /** Header routing-service adds to routed records: ROUTED or REQUIRES_REVIEW. */
  public static final String ROUTING_STATUS_HEADER = "routingStatus";

  private final InstructionProjection projection;
  private final AuditWriter audit;

  /**
   * Creates the listener.
   *
   * @param projection instruction read model
   * @param audit audit trail
   */
  public RoutedInstructionListener(InstructionProjection projection, AuditWriter audit) {
    this.projection = projection;
    this.audit = audit;
  }

  /**
   * Handles one routed instruction.
   *
   * @param record the consumed record
   */
  @KafkaListener(
      id = LISTENER_ID,
      idIsGroup = false,
      topics = {Topics.ROUTED_EFT, Topics.ROUTED_WIRE, Topics.ROUTED_INTERAC})
  public void onRouted(ConsumerRecord<String, Object> record) {
    EventMetadata meta = EventMetadata.of(record);
    PaymentInstruction instruction = EventMetadata.payload(record, PaymentInstruction.class);
    InstructionStatus routingStatus =
        EventHeaders.lastValue(record.headers(), ROUTING_STATUS_HEADER)
            .filter("REQUIRES_REVIEW"::equals)
            .map(InstructionStatus::valueOf)
            .orElse(InstructionStatus.ROUTED);
    String batchId = instruction.batchId().toString();
    MDC.put(MdcKeys.CORRELATION_ID, batchId);
    MDC.put(MdcKeys.IDEMPOTENCY_KEY, instruction.idempotencyKey());
    try {
      projection.applyRouted(instruction, routingStatus, meta.occurredAt());
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("topic", record.topic());
      details.put("channel", instruction.channel());
      details.put("routingStatus", routingStatus);
      details.put("instructionRef", instruction.instructionRef());
      details.put("amount", instruction.amount());
      details.put("currency", instruction.currency());
      details.put(
          "debtorAccount", instruction.debtor() == null ? null : instruction.debtor().account());
      details.put(
          "creditorAccount",
          instruction.creditor() == null ? null : instruction.creditor().account());
      details.put("replayCount", instruction.replayCount());
      audit.recordEvent(
          meta.eventId(),
          AuditEventType.INSTRUCTION_ROUTED,
          AuditEntityType.INSTRUCTION,
          instruction.idempotencyKey(),
          meta.occurredAt(),
          batchId,
          details);
    } finally {
      MDC.remove(MdcKeys.CORRELATION_ID);
      MDC.remove(MdcKeys.IDEMPOTENCY_KEY);
    }
  }
}

package ca.musakaleem.remit.common.event;

/** Values of the {@code eventType} Kafka header, one per kind of event Remit publishes. */
public enum EventType {
  PAYMENT_INSTRUCTION,
  INSTRUCTION_REJECTED,
  BATCH_RECEIVED,
  BATCH_COMPLETED,
  INSTRUCTION_STATUS,
  /** An instruction published by routing-service to its {@code payments.routed.*} topic. */
  INSTRUCTION_ROUTED
}

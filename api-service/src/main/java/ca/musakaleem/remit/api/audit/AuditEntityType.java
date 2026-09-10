package ca.musakaleem.remit.api.audit;

/** What an audit document is about; {@code entityId} holds the matching identifier. */
public enum AuditEntityType {
  /** {@code entityId} is a batch id. */
  BATCH,
  /** {@code entityId} is an instruction idempotency key. */
  INSTRUCTION,
  /** {@code entityId} is a username. */
  USER,
  /** {@code entityId} is a dead letter id; used for poison pills, which have no instruction key. */
  DEAD_LETTER
}

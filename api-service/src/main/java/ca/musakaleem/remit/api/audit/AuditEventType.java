package ca.musakaleem.remit.api.audit;

/**
 * Kinds of audit documents (spec 8.2). The spec's eight types are extended with two so that every
 * consumed record maps to exactly one type (docs/decisions/0009-audit-event-types.md).
 */
public enum AuditEventType {
  /** Batch event with status RECEIVED. */
  BATCH_RECEIVED,
  /** Batch event with a final status: COMPLETED, COMPLETED_WITH_ERRORS or FAILED. */
  BATCH_COMPLETED,
  /** An instruction published by routing-service to its {@code payments.routed.*} topic. */
  INSTRUCTION_ROUTED,
  /** A status event other than DEAD_LETTERED: ROUTED, REQUIRES_REVIEW, DUPLICATE or REPLAYED. */
  INSTRUCTION_STATUS_CHANGED,
  /** A row rejected by intake-service. */
  INSTRUCTION_REJECTED,
  /** A DEAD_LETTERED status event. */
  INSTRUCTION_DEAD_LETTERED,
  /** A record consumed from {@code payments.instructions.DLT}. */
  DEAD_LETTER_RECORDED,
  /** An administrator replayed a dead letter. */
  DLT_REPLAY_REQUESTED,
  /** A successful login. */
  LOGIN_SUCCESS,
  /** A refused login: bad credentials, disabled account or locked username. */
  LOGIN_FAILURE
}

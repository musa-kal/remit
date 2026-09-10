package ca.musakaleem.remit.api.user;

/**
 * Roles of an operator account (FR-SEC-03). They are independent: an ADMIN account also holds
 * OPERATOR to read data.
 */
public enum Role {
  /** Reads batches, instructions, rejections, dead letters and statistics. */
  OPERATOR,
  /** Replays dead letters and reads the audit trail. */
  ADMIN
}

package ca.musakaleem.remit.routing.ledger;

/** Status of a row in {@code processed_instruction} (spec 8.1). */
public enum ProcessingStatus {
  /** An attempt claimed the key and has not finished; a redelivery may continue it. */
  ROUTING,
  /** Published to its channel topic. */
  ROUTED,
  /** Published to its channel topic, flagged for manual review (WIRE at or above threshold). */
  REQUIRES_REVIEW;

  /**
   * Whether routing finished for this instruction, so a new delivery is a duplicate.
   *
   * @return true for ROUTED and REQUIRES_REVIEW
   */
  public boolean isFinal() {
    return this != ROUTING;
  }
}

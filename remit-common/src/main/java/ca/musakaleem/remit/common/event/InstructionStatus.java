package ca.musakaleem.remit.common.event;

/** Routing outcome of a single payment instruction. */
public enum InstructionStatus {
  ROUTED,
  REQUIRES_REVIEW,
  DUPLICATE,
  DEAD_LETTERED,
  REPLAYED
}

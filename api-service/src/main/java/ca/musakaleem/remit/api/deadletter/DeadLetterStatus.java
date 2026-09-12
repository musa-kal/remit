package ca.musakaleem.remit.api.deadletter;

/** Lifecycle of a dead letter in {@code remit_api.dead_letter}. */
public enum DeadLetterStatus {
  /** On the DLT and not replayed, or its last replay failed again. */
  PENDING,
  /** Republished by an administrator; routing-service has not reported the outcome yet. */
  REPLAY_REQUESTED,
  /** The replay was routed (REPLAYED, ROUTED or REQUIRES_REVIEW status event). */
  REPLAYED,
  /** Replayed as often as allowed (3 times) without success; cannot be replayed again. */
  EXHAUSTED
}

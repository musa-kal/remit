package ca.musakaleem.remit.api.deadletter;

/**
 * What applying a DLT record did.
 *
 * @param deadLetter the affected row
 * @param kind what happened to it
 */
public record DeadLetterOutcome(DeadLetter deadLetter, Kind kind) {

  /** The possible results. */
  public enum Kind {
    /** A new dead letter. */
    CREATED,
    /** An open dead letter of the same instruction failed again, usually its replay. */
    FAILED_AGAIN,
    /** A record seen before; nothing changed. */
    ALREADY_RECORDED
  }
}

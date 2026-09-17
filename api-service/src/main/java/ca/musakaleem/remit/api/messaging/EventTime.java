package ca.musakaleem.remit.api.messaging;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Brings instants to the precision of the {@code TIMESTAMP(3)} columns before they are stored or
 * compared. MySQL rounds extra fractional digits, so without this an event at {@code .1236} would
 * be stored as {@code .124}, and a later event at {@code .1238} would look older than it and be
 * ignored.
 */
public final class EventTime {

  private EventTime() {}

  /**
   * Truncates to whole milliseconds.
   *
   * @param instant an instant, may be {@code null}
   * @return the truncated instant, or {@code null}
   */
  public static Instant millis(Instant instant) {
    return instant == null ? null : instant.truncatedTo(ChronoUnit.MILLIS);
  }
}

package ca.musakaleem.remit.api.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A UTC clock that tests move forward by hand. */
public final class MutableClock extends Clock {

  private Instant now;

  /**
   * Creates the clock.
   *
   * @param start initial instant
   */
  public MutableClock(Instant start) {
    this.now = start;
  }

  /**
   * Moves the clock forward.
   *
   * @param duration how far
   */
  public synchronized void advance(Duration duration) {
    now = now.plus(duration);
  }

  @Override
  public synchronized Instant instant() {
    return now;
  }

  @Override
  public ZoneId getZone() {
    return ZoneOffset.UTC;
  }

  @Override
  public Clock withZone(ZoneId zone) {
    throw new UnsupportedOperationException("MutableClock is UTC only");
  }
}

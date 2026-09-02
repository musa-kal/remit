package ca.musakaleem.remit.intake.config;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/** Business date rules: dates are judged in America/Toronto, instants are stored in UTC. */
public final class BusinessCalendar {

  /** Zone whose calendar date is "today" for value date checks (NFR-04). */
  public static final ZoneId BUSINESS_ZONE = ZoneId.of("America/Toronto");

  private BusinessCalendar() {}

  /**
   * Returns today's business date.
   *
   * @param clock the injected clock
   * @return the calendar date in America/Toronto
   */
  public static LocalDate today(Clock clock) {
    return LocalDate.now(clock.withZone(BUSINESS_ZONE));
  }

  /**
   * Returns a clock that keeps ticking but whose business date is {@code businessDate}. Only the
   * date moves; the time of day in Toronto stays the same, so timestamps still increase.
   *
   * @param base the real clock
   * @param businessDate the date "today" should be
   * @return a UTC clock offset from {@code base}
   */
  public static Clock shiftedTo(Clock base, LocalDate businessDate) {
    ZonedDateTime now = ZonedDateTime.now(base.withZone(BUSINESS_ZONE));
    ZonedDateTime shifted = now.with(businessDate);
    return Clock.offset(base.withZone(ZoneOffset.UTC), Duration.between(now, shifted));
  }
}

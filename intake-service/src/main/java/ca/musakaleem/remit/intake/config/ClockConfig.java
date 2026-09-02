package ca.musakaleem.remit.intake.config;

import java.time.Clock;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides the {@link Clock} every time-dependent rule uses. {@code remit.clock.fixed-date} pins
 * the business date for demos and tests (see docs/decisions/0005-demo-clock.md).
 */
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

  private static final Logger log = LoggerFactory.getLogger(ClockConfig.class);

  /**
   * Creates the system UTC clock, shifted to a fixed business date when one is configured.
   *
   * @param fixedDate ISO date such as {@code 2026-10-15}, or blank for the real date
   * @return the clock
   */
  @Bean
  public Clock clock(@Value("${remit.clock.fixed-date:}") String fixedDate) {
    Clock system = Clock.systemUTC();
    if (fixedDate == null || fixedDate.isBlank()) {
      return system;
    }
    LocalDate date = LocalDate.parse(fixedDate.trim());
    log.warn(
        "Business date pinned to {} by remit.clock.fixed-date; use only for demos and tests", date);
    return BusinessCalendar.shiftedTo(system, date);
  }
}

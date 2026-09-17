package ca.musakaleem.remit.api.config;

import java.time.Clock;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides the UTC {@link Clock} used for token times, login lockouts, replay and audit timestamps
 * (NFR-04). It ticks in whole milliseconds, the precision of the {@code TIMESTAMP(3)} columns.
 */
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

  /**
   * The system clock in UTC, truncated to milliseconds.
   *
   * @return the clock
   */
  @Bean
  public Clock clock() {
    return Clock.tick(Clock.systemUTC(), Duration.ofMillis(1));
  }
}

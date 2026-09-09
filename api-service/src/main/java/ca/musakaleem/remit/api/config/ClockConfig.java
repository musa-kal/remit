package ca.musakaleem.remit.api.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides the UTC {@link Clock} used for token times, login lockouts, replay and audit timestamps
 * (NFR-04). Tests replace it to control time.
 */
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

  /**
   * The system clock in UTC.
   *
   * @return the clock
   */
  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }
}

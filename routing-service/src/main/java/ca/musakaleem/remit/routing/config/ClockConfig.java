package ca.musakaleem.remit.routing.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides the UTC {@link Clock} used for ledger timestamps and event times (NFR-04). Routing
 * applies no business date rules, so it has no fixed-date override.
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

package ca.musakaleem.remit.routing.config;

import ca.musakaleem.remit.routing.deadletter.DeadLetterDestination;
import ca.musakaleem.remit.routing.deadletter.DeadLetterStatusRecoverer;
import ca.musakaleem.remit.routing.error.NonRetryableRoutingException;
import ca.musakaleem.remit.routing.publish.RoutingEventPublisher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.util.backoff.BackOff;

/**
 * Retry and dead-letter policy of the instruction listener (FR-RTE-03,
 * docs/decisions/0003-dlt-and-replay.md). Boot installs the {@link DefaultErrorHandler} bean in the
 * listener container factory.
 */
@Configuration(proxyBeanMethods = false)
public class ErrorHandlingConfig {

  /** Delivery attempts in total: the first one plus two retries. */
  public static final int MAX_ATTEMPTS = 3;

  /** Wait before the first retry. */
  public static final long INITIAL_INTERVAL_MS = 1_000;

  /** Each retry waits this many times longer than the one before (1 s, then 2 s). */
  public static final double MULTIPLIER = 2.0;

  /**
   * Publishes failed records to {@code payments.instructions.DLT} with the original key, value and
   * headers plus Spring's exception and origin headers.
   *
   * @param kafkaTemplate the application's template (JSON, or raw bytes for poison pills)
   * @param deadLetterPartitions partitions of the dead-letter topic
   * @return the recoverer
   */
  @Bean
  public DeadLetterPublishingRecoverer deadLetterPublishingRecoverer(
      KafkaTemplate<?, ?> kafkaTemplate,
      @Value("${remit.kafka.partitions:3}") int deadLetterPartitions) {
    return new DeadLetterPublishingRecoverer(
        kafkaTemplate, new DeadLetterDestination(deadLetterPartitions));
  }

  /**
   * Retries retryable failures with exponential back-off, then dead-letters them and publishes
   * DEAD_LETTERED. {@link NonRetryableRoutingException} and deserialization failures (poison pills,
   * not retryable by Spring's defaults) are dead-lettered on the first attempt.
   *
   * @param deadLetterPublishingRecoverer the DLT publisher
   * @param publisher status event publisher
   * @return the error handler
   */
  @Bean
  public DefaultErrorHandler errorHandler(
      DeadLetterPublishingRecoverer deadLetterPublishingRecoverer,
      RoutingEventPublisher publisher) {
    DefaultErrorHandler handler =
        new DefaultErrorHandler(
            new DeadLetterStatusRecoverer(deadLetterPublishingRecoverer, publisher), backOff());
    handler.addNotRetryableExceptions(NonRetryableRoutingException.class);
    return handler;
  }

  /**
   * Exponential back-off: 1 s, then 2 s, then give up (three attempts in total).
   *
   * @return the back-off
   */
  static BackOff backOff() {
    ExponentialBackOffWithMaxRetries backOff =
        new ExponentialBackOffWithMaxRetries(MAX_ATTEMPTS - 1);
    backOff.setInitialInterval(INITIAL_INTERVAL_MS);
    backOff.setMultiplier(MULTIPLIER);
    return backOff;
  }
}

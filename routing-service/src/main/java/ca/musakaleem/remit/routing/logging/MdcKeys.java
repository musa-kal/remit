package ca.musakaleem.remit.routing.logging;

/**
 * MDC keys written into every JSON log line (NFR-01). The {@code service} key is a logback context
 * property set in logback-spring.xml.
 */
public final class MdcKeys {

  /** Batch id of the instruction being handled. */
  public static final String CORRELATION_ID = "correlationId";

  /** Idempotency key of the instruction being handled. */
  public static final String IDEMPOTENCY_KEY = "idempotencyKey";

  private MdcKeys() {}
}

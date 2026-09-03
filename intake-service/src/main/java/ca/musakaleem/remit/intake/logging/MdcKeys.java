package ca.musakaleem.remit.intake.logging;

/** MDC keys written into every JSON log line (NFR-01). */
public final class MdcKeys {

  /** Batch id of the file being processed. */
  public static final String CORRELATION_ID = "correlationId";

  /** Idempotency key of the instruction being handled. */
  public static final String IDEMPOTENCY_KEY = "idempotencyKey";

  /** Name of the file being processed. */
  public static final String FILE_NAME = "fileName";

  private MdcKeys() {}
}

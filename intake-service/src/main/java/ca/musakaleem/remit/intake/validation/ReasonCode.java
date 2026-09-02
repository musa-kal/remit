package ca.musakaleem.remit.intake.validation;

/** Why a row, a whole file or a batch was rejected. Codes are published and written to logs. */
public enum ReasonCode {
  // Row level (spec 3.3): the row is published to payments.rejected, the rest of the file goes on.
  MALFORMED_ROW(Scope.ROW),
  INVALID_INSTRUCTION_REF(Scope.ROW),
  DUPLICATE_INSTRUCTION_REF_IN_FILE(Scope.ROW),
  INVALID_NAME(Scope.ROW),
  INVALID_INSTITUTION(Scope.ROW),
  INVALID_TRANSIT(Scope.ROW),
  INVALID_ACCOUNT(Scope.ROW),
  INVALID_AMOUNT(Scope.ROW),
  INVALID_CURRENCY(Scope.ROW),
  INVALID_CHANNEL(Scope.ROW),
  INVALID_VALUE_DATE(Scope.ROW),
  REMITTANCE_TOO_LONG(Scope.ROW),
  CHANNEL_LIMIT_EXCEEDED(Scope.ROW),
  CHANNEL_CURRENCY_NOT_ALLOWED(Scope.ROW),

  // File level (spec 3.1, 3.4): nothing is published except a FAILED batch event.
  INVALID_FILENAME(Scope.FILE),
  INVALID_ENCODING(Scope.FILE),
  INVALID_HEADER(Scope.FILE),
  INVALID_TRAILER(Scope.FILE),
  RECORD_COUNT_MISMATCH(Scope.FILE),
  CONTROL_TOTAL_MISMATCH(Scope.FILE),
  DUPLICATE_FILE(Scope.FILE),
  EMPTY_FILE(Scope.FILE),

  // Batch level (spec 3.5): rows were processed but the batch as a whole ends FAILED.
  REJECTION_THRESHOLD_EXCEEDED(Scope.BATCH),
  SKIP_LIMIT_EXCEEDED(Scope.BATCH);

  /** Granularity a reason code applies to. */
  public enum Scope {
    ROW,
    FILE,
    BATCH
  }

  private final Scope scope;

  ReasonCode(Scope scope) {
    this.scope = scope;
  }

  /**
   * Returns the granularity this code applies to.
   *
   * @return row, file or batch
   */
  public Scope scope() {
    return scope;
  }
}

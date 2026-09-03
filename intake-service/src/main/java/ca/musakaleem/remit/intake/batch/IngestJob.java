package ca.musakaleem.remit.intake.batch;

/** Names of the ingest job, its steps, parameters, execution context keys and exit codes. */
public final class IngestJob {

  /** Spring Batch job name (FR-INT-03). */
  public static final String JOB_NAME = "ingestPaymentFileJob";

  /** Tasklet step running the file-level checks. */
  public static final String FILE_VALIDATION_STEP = "fileValidationStep";

  /** Chunk step validating and publishing each row. */
  public static final String INSTRUCTION_STEP = "instructionStep";

  /** Tasklet step computing the final batch status. */
  public static final String FINALIZE_STEP = "finalizeStep";

  /** Job parameter: original file name. */
  public static final String FILE_NAME = "fileName";

  /** Job parameter: client id parsed from the file name. */
  public static final String CLIENT_ID = "clientId";

  /** Job parameter: SHA-256 hex of the file content. */
  public static final String FILE_HASH = "fileHash";

  /** Job parameter: absolute path of the staged local copy. */
  public static final String STAGED_PATH = "stagedPath";

  /** Job context: batch id assigned by the validation step. */
  public static final String BATCH_ID = "batchId";

  /** Job context: number of instruction rows declared in the trailer. */
  public static final String RECORD_COUNT = "recordCount";

  /** Job context: control total declared in the trailer, plain string. */
  public static final String CONTROL_TOTAL = "controlTotal";

  /** Job context: comma separated line numbers whose instruction_ref repeats an earlier row. */
  public static final String DUPLICATE_REF_LINES = "duplicateRefLines";

  /** Job context: final batch status, set when the batch is FAILED or finished. */
  public static final String BATCH_STATUS = "batchStatus";

  /** Job context: reason code of a rejected file or FAILED batch. */
  public static final String REJECT_REASON = "rejectReason";

  /** Job context: human readable detail for {@link #REJECT_REASON}. */
  public static final String REJECT_DETAIL = "rejectDetail";

  /** Exit code of the validation step (and the job) when the file is rejected. */
  public static final String REJECTED = "REJECTED";

  /** Exit code of the instruction step when the skip limit stopped it. */
  public static final String SKIP_LIMIT_EXCEEDED = "SKIP_LIMIT_EXCEEDED";

  private IngestJob() {}
}

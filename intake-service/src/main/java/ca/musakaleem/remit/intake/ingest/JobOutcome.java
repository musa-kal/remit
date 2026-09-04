package ca.musakaleem.remit.intake.ingest;

import ca.musakaleem.remit.intake.batch.IngestJob;
import ca.musakaleem.remit.intake.validation.ReasonCode;
import java.nio.file.Path;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.item.ExecutionContext;

/**
 * What happened to one file's job, and therefore where the remote file goes next.
 *
 * @param executionId job execution id, or {@code null} when no execution was created
 * @param fileName file name
 * @param stagedPath local staged copy
 * @param disposition where the remote file goes
 * @param reasonCode reason for a rejected file or FAILED batch, otherwise {@code null}
 * @param detail explanation, otherwise {@code null}
 */
public record JobOutcome(
    Long executionId,
    String fileName,
    Path stagedPath,
    Disposition disposition,
    String reasonCode,
    String detail) {

  /** Where the remote file goes after the job. */
  public enum Disposition {
    /** Batch COMPLETED or COMPLETED_WITH_ERRORS: move to /processed. */
    PROCESSED,
    /** File rejected or batch FAILED: move to /error with a note. */
    REJECTED,
    /** Job failed technically: leave in /processing for a restart. */
    INCOMPLETE
  }

  /**
   * Derives the outcome from a finished job execution.
   *
   * @param execution the execution returned by the job launcher
   * @return the outcome
   */
  public static JobOutcome from(JobExecution execution) {
    JobParameters params = execution.getJobParameters();
    String fileName = params.getString(IngestJob.FILE_NAME);
    Path stagedPath = Path.of(params.getString(IngestJob.STAGED_PATH));
    Long id = execution.getId();
    if (execution.getStatus() != BatchStatus.COMPLETED) {
      return new JobOutcome(
          id,
          fileName,
          stagedPath,
          Disposition.INCOMPLETE,
          null,
          "job " + execution.getStatus() + ": " + execution.getExitStatus().getExitDescription());
    }
    ExecutionContext context = execution.getExecutionContext();
    String reason = context.getString(IngestJob.REJECT_REASON, null);
    if (reason != null) {
      return new JobOutcome(
          id,
          fileName,
          stagedPath,
          Disposition.REJECTED,
          reason,
          context.getString(IngestJob.REJECT_DETAIL, ""));
    }
    return new JobOutcome(id, fileName, stagedPath, Disposition.PROCESSED, null, null);
  }

  /**
   * Outcome for a file whose job instance (same name and content) already completed.
   *
   * @param staged the staged file
   * @return a DUPLICATE_FILE rejection
   */
  public static JobOutcome alreadyProcessed(StagedFile staged) {
    return new JobOutcome(
        null,
        staged.fileName(),
        staged.path(),
        Disposition.REJECTED,
        ReasonCode.DUPLICATE_FILE.name(),
        "a file with the same name and content was already processed");
  }

  /**
   * Outcome for a file whose job could not be started or finished.
   *
   * @param staged the staged file
   * @param detail why
   * @return an incomplete outcome
   */
  public static JobOutcome notRun(StagedFile staged, String detail) {
    return new JobOutcome(
        null, staged.fileName(), staged.path(), Disposition.INCOMPLETE, null, detail);
  }

  /**
   * Text written to {@code <file>.error.txt}: the reason code on the first line, then the detail.
   *
   * @return note content
   */
  public String errorNote() {
    return reasonCode + "\n" + (detail == null ? "" : detail + "\n");
  }
}

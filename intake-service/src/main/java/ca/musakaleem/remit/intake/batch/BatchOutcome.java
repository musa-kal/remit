package ca.musakaleem.remit.intake.batch;

import ca.musakaleem.remit.common.event.BatchStatus;
import ca.musakaleem.remit.intake.validation.ReasonCode;

/**
 * Final verdict for a batch whose rows were processed (spec 3.5).
 *
 * @param status COMPLETED, COMPLETED_WITH_ERRORS or FAILED
 * @param reason why the batch FAILED, otherwise {@code null}
 */
public record BatchOutcome(BatchStatus status, ReasonCode reason) {

  /**
   * Decides the final status from the row counts.
   *
   * @param totalRows rows declared in the trailer
   * @param rejectedRows rows published to payments.rejected
   * @param skipLimitHit whether the step stopped because the skip limit was reached
   * @param skipLimit {@code remit.intake.skip-limit}
   * @return the outcome
   */
  public static BatchOutcome decide(
      int totalRows, int rejectedRows, boolean skipLimitHit, int skipLimit) {
    if (skipLimitHit || rejectedRows > skipLimit) {
      return new BatchOutcome(BatchStatus.FAILED, ReasonCode.SKIP_LIMIT_EXCEEDED);
    }
    // "More than 50%" without floating point: rejected / total > 1 / 2.
    if ((long) rejectedRows * 2 > totalRows) {
      return new BatchOutcome(BatchStatus.FAILED, ReasonCode.REJECTION_THRESHOLD_EXCEEDED);
    }
    if (rejectedRows > 0) {
      return new BatchOutcome(BatchStatus.COMPLETED_WITH_ERRORS, null);
    }
    return new BatchOutcome(BatchStatus.COMPLETED, null);
  }
}

package ca.musakaleem.remit.intake.batch;

import ca.musakaleem.remit.intake.validation.InstructionValidationException;
import org.springframework.batch.core.step.skip.SkipLimitExceededException;
import org.springframework.batch.core.step.skip.SkipPolicy;

/**
 * Skips rows that failed validation, up to {@code remit.intake.skip-limit} per step execution. Any
 * other exception is not skippable and fails the step, which keeps the job restartable.
 */
public class InstructionSkipPolicy implements SkipPolicy {

  private final long skipLimit;

  /**
   * Creates the policy.
   *
   * @param skipLimit most rows that may be skipped
   */
  public InstructionSkipPolicy(long skipLimit) {
    this.skipLimit = skipLimit;
  }

  @Override
  public boolean shouldSkip(Throwable t, long skipCount) {
    if (!(t instanceof InstructionValidationException)) {
      return false;
    }
    if (skipCount >= skipLimit) {
      throw new SkipLimitExceededException(skipLimit, t);
    }
    return true;
  }
}

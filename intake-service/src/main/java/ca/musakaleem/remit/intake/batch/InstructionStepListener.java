package ca.musakaleem.remit.intake.batch;

import ca.musakaleem.remit.intake.validation.InstructionValidationException;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.core.step.skip.SkipLimitExceededException;

/**
 * Gives the instruction step the exit code {@code SKIP_LIMIT_EXCEEDED} when it stopped because too
 * many rows were invalid, so the job goes on to finalize the batch as FAILED instead of failing as
 * if something technical had broken.
 */
public class InstructionStepListener implements StepExecutionListener {

  @Override
  public ExitStatus afterStep(StepExecution stepExecution) {
    boolean skipLimitHit =
        stepExecution.getStatus() == BatchStatus.FAILED
            && stepExecution.getFailureExceptions().stream().anyMatch(this::causedBySkipLimit);
    if (skipLimitHit) {
      return new ExitStatus(IngestJob.SKIP_LIMIT_EXCEEDED, "too many rejected rows");
    }
    return null;
  }

  private boolean causedBySkipLimit(Throwable failure) {
    for (Throwable t = failure; t != null; t = t.getCause()) {
      if (t instanceof SkipLimitExceededException || t instanceof InstructionValidationException) {
        return true;
      }
    }
    return false;
  }
}

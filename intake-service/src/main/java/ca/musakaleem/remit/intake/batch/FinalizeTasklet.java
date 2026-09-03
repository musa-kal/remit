package ca.musakaleem.remit.intake.batch;

import ca.musakaleem.remit.common.event.BatchEvent;
import ca.musakaleem.remit.common.event.BatchStatus;
import ca.musakaleem.remit.intake.config.IntakeProperties;
import ca.musakaleem.remit.intake.publish.IntakeEventPublisher;
import ca.musakaleem.remit.intake.registry.FileRegistryEntry;
import ca.musakaleem.remit.intake.registry.FileRegistryRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.repeat.RepeatStatus;

/**
 * {@code finalizeStep}: totals the instruction step over every execution of this job instance (a
 * restarted job has more than one), decides COMPLETED, COMPLETED_WITH_ERRORS or FAILED (spec 3.5),
 * updates the file registry and publishes the final batch event.
 */
public class FinalizeTasklet implements Tasklet {

  private final JobExplorer jobExplorer;
  private final FileRegistryRepository registry;
  private final IntakeEventPublisher publisher;
  private final Clock clock;
  private final int skipLimit;

  /**
   * Creates the tasklet.
   *
   * @param jobExplorer reads step counts of earlier executions of the same job instance
   * @param registry file registry
   * @param publisher event publisher
   * @param clock clock for the completion timestamp
   * @param properties provides the skip limit
   */
  public FinalizeTasklet(
      JobExplorer jobExplorer,
      FileRegistryRepository registry,
      IntakeEventPublisher publisher,
      Clock clock,
      IntakeProperties properties) {
    this.jobExplorer = jobExplorer;
    this.registry = registry;
    this.publisher = publisher;
    this.clock = clock;
    this.skipLimit = properties.skipLimit();
  }

  @Override
  public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
    JobExecution jobExecution = contribution.getStepExecution().getJobExecution();
    JobParameters params = jobExecution.getJobParameters();
    ExecutionContext jobContext = jobExecution.getExecutionContext();
    UUID batchId = UUID.fromString(jobContext.getString(IngestJob.BATCH_ID));
    int totalRows = jobContext.getInt(IngestJob.RECORD_COUNT);
    BigDecimal controlTotal = new BigDecimal(jobContext.getString(IngestJob.CONTROL_TOTAL));

    InstructionCounts counts = countInstructions(jobExecution);
    BatchOutcome outcome =
        BatchOutcome.decide(totalRows, counts.rejected(), counts.skipLimitHit(), skipLimit);
    Instant now = clock.instant();

    FileRegistryEntry entry =
        registry
            .findByBatchId(batchId.toString())
            .orElseThrow(() -> new IllegalStateException("No file_registry row for " + batchId));
    entry.complete(outcome.status(), outcome.reason(), now);
    registry.saveAndFlush(entry);

    publisher.publishBatchEvent(
        new BatchEvent(
            batchId,
            params.getString(IngestJob.CLIENT_ID),
            params.getString(IngestJob.FILE_NAME),
            params.getString(IngestJob.FILE_HASH),
            outcome.status(),
            totalRows,
            counts.accepted(),
            counts.rejected(),
            controlTotal,
            now),
        outcome.reason());

    jobContext.putString(IngestJob.BATCH_STATUS, outcome.status().name());
    if (outcome.status() == BatchStatus.FAILED) {
      jobContext.putString(IngestJob.REJECT_REASON, outcome.reason().name());
      jobContext.putString(
          IngestJob.REJECT_DETAIL,
          counts.rejected() + " of " + totalRows + " rows rejected (skip limit " + skipLimit + ")");
    }
    return RepeatStatus.FINISHED;
  }

  private InstructionCounts countInstructions(JobExecution current) {
    int accepted = 0;
    int rejected = 0;
    boolean skipLimitHit = false;
    for (JobExecution execution : jobExplorer.getJobExecutions(current.getJobInstance())) {
      for (StepExecution step : execution.getStepExecutions()) {
        if (IngestJob.INSTRUCTION_STEP.equals(step.getStepName())) {
          accepted += (int) step.getWriteCount();
          rejected += (int) step.getSkipCount();
          skipLimitHit |= IngestJob.SKIP_LIMIT_EXCEEDED.equals(step.getExitStatus().getExitCode());
        }
      }
    }
    return new InstructionCounts(accepted, rejected, skipLimitHit);
  }

  private record InstructionCounts(int accepted, int rejected, boolean skipLimitHit) {}
}

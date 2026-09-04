package ca.musakaleem.remit.intake.ingest;

import ca.musakaleem.remit.intake.batch.IngestJob;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.stereotype.Component;

/**
 * Actuator endpoint {@code /actuator/ingestjobs}: GET lists recent ingest job executions, POST
 * {@code /actuator/ingestjobs/{executionId}} restarts a FAILED one and then moves its remote file
 * like the poller would (FR-INT-06, see docs/runbook.md).
 */
@Component
@Endpoint(id = "ingestjobs")
public class IngestJobsEndpoint {

  private static final int RECENT_INSTANCES = 20;

  private final JobExplorer jobExplorer;
  private final IngestJobService jobs;
  private final InboundFileProcessor processor;

  /**
   * Creates the endpoint.
   *
   * @param jobExplorer reads job executions
   * @param jobs restarts executions
   * @param processor moves the remote file after a restart
   */
  public IngestJobsEndpoint(
      JobExplorer jobExplorer, IngestJobService jobs, InboundFileProcessor processor) {
    this.jobExplorer = jobExplorer;
    this.jobs = jobs;
    this.processor = processor;
  }

  /**
   * Lists the latest execution of the most recent job instances.
   *
   * @return newest first
   */
  @ReadOperation
  public List<ExecutionSummary> recent() {
    return jobExplorer.getJobInstances(IngestJob.JOB_NAME, 0, RECENT_INSTANCES).stream()
        .map(jobExplorer::getLastJobExecution)
        .filter(Objects::nonNull)
        .map(ExecutionSummary::of)
        .toList();
  }

  /**
   * Restarts a failed execution.
   *
   * @param executionId execution to restart
   * @return the outcome of the new execution
   */
  @WriteOperation
  public JobOutcomeView restart(@Selector long executionId) {
    JobOutcome outcome = jobs.restart(executionId);
    processor.complete(outcome);
    return new JobOutcomeView(
        outcome.executionId(),
        outcome.fileName(),
        outcome.disposition().name(),
        outcome.reasonCode(),
        outcome.detail());
  }

  /**
   * One execution in the listing.
   *
   * @param executionId execution id
   * @param instanceId job instance id
   * @param fileName file name parameter
   * @param status Spring Batch status
   * @param exitCode exit code
   * @param batchStatus Remit batch status, when known
   * @param startTime start time
   * @param endTime end time
   */
  public record ExecutionSummary(
      Long executionId,
      Long instanceId,
      String fileName,
      String status,
      String exitCode,
      String batchStatus,
      LocalDateTime startTime,
      LocalDateTime endTime) {

    static ExecutionSummary of(JobExecution execution) {
      return new ExecutionSummary(
          execution.getId(),
          execution.getJobInstance().getInstanceId(),
          execution.getJobParameters().getString(IngestJob.FILE_NAME),
          execution.getStatus().name(),
          execution.getExitStatus().getExitCode(),
          execution.getExecutionContext().getString(IngestJob.BATCH_STATUS, null),
          execution.getStartTime(),
          execution.getEndTime());
    }
  }

  /**
   * Result of a restart.
   *
   * @param executionId new execution id
   * @param fileName file name
   * @param disposition PROCESSED, REJECTED or INCOMPLETE
   * @param reasonCode reason for a rejection, if any
   * @param detail detail, if any
   */
  public record JobOutcomeView(
      Long executionId, String fileName, String disposition, String reasonCode, String detail) {}
}

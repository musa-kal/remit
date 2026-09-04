package ca.musakaleem.remit.intake.ingest;

import ca.musakaleem.remit.intake.batch.IngestJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionException;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.stereotype.Service;

/** Launches {@code ingestPaymentFileJob} for a staged file and restarts failed executions. */
@Service
public class IngestJobService {

  private static final Logger log = LoggerFactory.getLogger(IngestJobService.class);

  private final JobLauncher jobLauncher;
  private final Job ingestPaymentFileJob;
  private final JobExplorer jobExplorer;

  /**
   * Creates the service.
   *
   * @param jobLauncher synchronous job launcher
   * @param ingestPaymentFileJob the job
   * @param jobExplorer reads earlier executions for restarts
   */
  public IngestJobService(
      JobLauncher jobLauncher, Job ingestPaymentFileJob, JobExplorer jobExplorer) {
    this.jobLauncher = jobLauncher;
    this.ingestPaymentFileJob = ingestPaymentFileJob;
    this.jobExplorer = jobExplorer;
  }

  /**
   * Runs the job for a staged file and waits for it to finish (FR-INT-03).
   *
   * @param staged staged file
   * @param clientId client id from the file name
   * @return the outcome
   */
  public JobOutcome launch(StagedFile staged, String clientId) {
    JobParameters params =
        new JobParametersBuilder()
            .addString(IngestJob.FILE_NAME, staged.fileName())
            .addString(IngestJob.CLIENT_ID, clientId)
            .addString(IngestJob.FILE_HASH, staged.fileHash())
            .addString(IngestJob.STAGED_PATH, staged.path().toString())
            .toJobParameters();
    try {
      return JobOutcome.from(jobLauncher.run(ingestPaymentFileJob, params));
    } catch (JobInstanceAlreadyCompleteException e) {
      log.warn("File {} with the same content was already processed", staged.fileName());
      return JobOutcome.alreadyProcessed(staged);
    } catch (JobExecutionException e) {
      log.error("Could not run the ingest job for {}: {}", staged.fileName(), e.getMessage());
      return JobOutcome.notRun(staged, e.getMessage());
    }
  }

  /**
   * Restarts a FAILED or STOPPED execution with its original parameters; Spring Batch skips the
   * completed steps and resumes the chunk step after its last committed chunk (FR-INT-06).
   *
   * @param executionId id of the execution to restart
   * @return the outcome of the new execution
   * @throws IllegalArgumentException when the execution does not exist or is not this job's
   * @throws IllegalStateException when the execution cannot be restarted
   */
  public JobOutcome restart(long executionId) {
    JobExecution previous = jobExplorer.getJobExecution(executionId);
    if (previous == null || !IngestJob.JOB_NAME.equals(previous.getJobInstance().getJobName())) {
      throw new IllegalArgumentException("No " + IngestJob.JOB_NAME + " execution " + executionId);
    }
    if (previous.getStatus() != BatchStatus.FAILED && previous.getStatus() != BatchStatus.STOPPED) {
      throw new IllegalStateException(
          "Execution " + executionId + " is " + previous.getStatus() + ", not FAILED or STOPPED");
    }
    log.info(
        "Restarting execution {} for {}",
        executionId,
        previous.getJobParameters().getString(IngestJob.FILE_NAME));
    try {
      return JobOutcome.from(jobLauncher.run(ingestPaymentFileJob, previous.getJobParameters()));
    } catch (JobExecutionException e) {
      throw new IllegalStateException("Restart of execution " + executionId + " failed", e);
    }
  }
}

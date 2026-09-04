package ca.musakaleem.remit.intake.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ca.musakaleem.remit.intake.batch.IngestJob;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.repository.JobRestartException;
import org.springframework.batch.test.MetaDataInstanceFactory;

class IngestJobServiceTest {

  private static final StagedFile STAGED =
      new StagedFile(
          "PAY_ACME01_20261015093000.csv",
          "abc",
          Path.of("/staging/abc/PAY_ACME01_20261015093000.csv"));

  private JobLauncher launcher;
  private Job job;
  private JobExplorer explorer;
  private IngestJobService service;

  @BeforeEach
  void setUp() {
    launcher = mock(JobLauncher.class);
    job = mock(Job.class);
    explorer = mock(JobExplorer.class);
    service = new IngestJobService(launcher, job, explorer);
  }

  @Test
  void launchesWithTheFourIdentifyingParameters() throws Exception {
    JobExecution done = execution(BatchStatus.COMPLETED, IngestJob.JOB_NAME);
    when(launcher.run(eq(job), any())).thenReturn(done);

    JobOutcome outcome = service.launch(STAGED, "ACME01");

    ArgumentCaptor<JobParameters> params = ArgumentCaptor.forClass(JobParameters.class);
    Mockito.verify(launcher).run(eq(job), params.capture());
    JobParameters p = params.getValue();
    assertThat(p.getString(IngestJob.FILE_NAME)).isEqualTo(STAGED.fileName());
    assertThat(p.getString(IngestJob.CLIENT_ID)).isEqualTo("ACME01");
    assertThat(p.getString(IngestJob.FILE_HASH)).isEqualTo("abc");
    assertThat(p.getString(IngestJob.STAGED_PATH)).isEqualTo(STAGED.path().toString());
    assertThat(p.getParameters().values()).allMatch(parameter -> parameter.isIdentifying());
    assertThat(outcome.disposition()).isEqualTo(JobOutcome.Disposition.PROCESSED);
  }

  @Test
  void alreadyCompletedInstanceIsADuplicateFile() throws Exception {
    when(launcher.run(eq(job), any())).thenThrow(new JobInstanceAlreadyCompleteException("done"));

    JobOutcome outcome = service.launch(STAGED, "ACME01");

    assertThat(outcome.disposition()).isEqualTo(JobOutcome.Disposition.REJECTED);
    assertThat(outcome.reasonCode()).isEqualTo("DUPLICATE_FILE");
  }

  @Test
  void jobThatCannotRunIsIncomplete() throws Exception {
    when(launcher.run(eq(job), any())).thenThrow(new JobExecutionAlreadyRunningException("busy"));

    JobOutcome outcome = service.launch(STAGED, "ACME01");

    assertThat(outcome.disposition()).isEqualTo(JobOutcome.Disposition.INCOMPLETE);
    assertThat(outcome.detail()).isEqualTo("busy");
  }

  @Test
  void restartRerunsAFailedExecutionWithItsParameters() throws Exception {
    JobExecution failed = execution(BatchStatus.FAILED, IngestJob.JOB_NAME);
    when(explorer.getJobExecution(7L)).thenReturn(failed);
    when(launcher.run(job, failed.getJobParameters()))
        .thenReturn(execution(BatchStatus.COMPLETED, IngestJob.JOB_NAME));

    assertThat(service.restart(7L).disposition()).isEqualTo(JobOutcome.Disposition.PROCESSED);
  }

  @Test
  void restartAcceptsAStoppedExecution() throws Exception {
    JobExecution stopped = execution(BatchStatus.STOPPED, IngestJob.JOB_NAME);
    when(explorer.getJobExecution(7L)).thenReturn(stopped);
    when(launcher.run(job, stopped.getJobParameters()))
        .thenReturn(execution(BatchStatus.COMPLETED, IngestJob.JOB_NAME));

    assertThat(service.restart(7L).disposition()).isEqualTo(JobOutcome.Disposition.PROCESSED);
  }

  @Test
  void restartRejectsUnknownOrForeignExecutions() {
    when(explorer.getJobExecution(8L)).thenReturn(execution(BatchStatus.FAILED, "otherJob"));

    assertThatIllegalArgumentException().isThrownBy(() -> service.restart(99L));
    assertThatIllegalArgumentException().isThrownBy(() -> service.restart(8L));
  }

  @Test
  void restartRejectsExecutionsThatAreNotFailed() {
    when(explorer.getJobExecution(7L))
        .thenReturn(execution(BatchStatus.COMPLETED, IngestJob.JOB_NAME));

    assertThatIllegalStateException()
        .isThrownBy(() -> service.restart(7L))
        .withMessageContaining("COMPLETED");
  }

  @Test
  void restartWrapsLauncherFailures() throws Exception {
    JobExecution failed = execution(BatchStatus.FAILED, IngestJob.JOB_NAME);
    when(explorer.getJobExecution(7L)).thenReturn(failed);
    when(launcher.run(job, failed.getJobParameters())).thenThrow(new JobRestartException("no"));

    assertThatIllegalStateException().isThrownBy(() -> service.restart(7L));
  }

  private static JobExecution execution(BatchStatus status, String jobName) {
    JobExecution execution =
        MetaDataInstanceFactory.createJobExecution(
            jobName,
            1L,
            7L,
            new JobParametersBuilder()
                .addString(IngestJob.FILE_NAME, STAGED.fileName())
                .addString(IngestJob.CLIENT_ID, "ACME01")
                .addString(IngestJob.FILE_HASH, "abc")
                .addString(IngestJob.STAGED_PATH, STAGED.path().toString())
                .toJobParameters());
    execution.setStatus(status);
    return execution;
  }
}

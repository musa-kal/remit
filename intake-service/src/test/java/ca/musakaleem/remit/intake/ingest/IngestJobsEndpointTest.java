package ca.musakaleem.remit.intake.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ca.musakaleem.remit.intake.batch.IngestJob;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.test.MetaDataInstanceFactory;

class IngestJobsEndpointTest {

  private final JobExplorer explorer = mock(JobExplorer.class);
  private final IngestJobService jobs = mock(IngestJobService.class);
  private final InboundFileProcessor processor = mock(InboundFileProcessor.class);
  private final IngestJobsEndpoint endpoint = new IngestJobsEndpoint(explorer, jobs, processor);

  @Test
  void listsTheLastExecutionOfRecentInstances() {
    JobInstance first = new JobInstance(1L, IngestJob.JOB_NAME);
    JobInstance second = new JobInstance(2L, IngestJob.JOB_NAME);
    JobExecution execution =
        MetaDataInstanceFactory.createJobExecution(
            IngestJob.JOB_NAME,
            1L,
            11L,
            new JobParametersBuilder().addString(IngestJob.FILE_NAME, "f.csv").toJobParameters());
    execution.setStatus(BatchStatus.FAILED);
    execution.getExecutionContext().putString(IngestJob.BATCH_STATUS, "FAILED");
    when(explorer.getJobInstances(IngestJob.JOB_NAME, 0, 20)).thenReturn(List.of(first, second));
    when(explorer.getLastJobExecution(first)).thenReturn(execution);

    List<IngestJobsEndpoint.ExecutionSummary> recent = endpoint.recent();

    assertThat(recent).hasSize(1);
    IngestJobsEndpoint.ExecutionSummary summary = recent.get(0);
    assertThat(summary.executionId()).isEqualTo(11L);
    assertThat(summary.instanceId()).isEqualTo(1L);
    assertThat(summary.fileName()).isEqualTo("f.csv");
    assertThat(summary.status()).isEqualTo("FAILED");
    assertThat(summary.batchStatus()).isEqualTo("FAILED");
  }

  @Test
  void restartRunsTheJobAndMovesTheRemoteFile() {
    JobOutcome outcome =
        new JobOutcome(
            12L, "f.csv", Path.of("/s/f.csv"), JobOutcome.Disposition.PROCESSED, null, null);
    when(jobs.restart(11L)).thenReturn(outcome);

    IngestJobsEndpoint.JobOutcomeView view = endpoint.restart(11L);

    verify(processor).complete(outcome);
    assertThat(view.executionId()).isEqualTo(12L);
    assertThat(view.disposition()).isEqualTo("PROCESSED");
    assertThat(view.fileName()).isEqualTo("f.csv");
  }
}

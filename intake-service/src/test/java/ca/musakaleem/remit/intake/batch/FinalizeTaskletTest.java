package ca.musakaleem.remit.intake.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ca.musakaleem.remit.common.event.BatchEvent;
import ca.musakaleem.remit.common.event.BatchStatus;
import ca.musakaleem.remit.intake.config.IntakeProperties;
import ca.musakaleem.remit.intake.publish.IntakeEventPublisher;
import ca.musakaleem.remit.intake.registry.FileRegistryEntry;
import ca.musakaleem.remit.intake.registry.FileRegistryRepository;
import ca.musakaleem.remit.intake.validation.ReasonCode;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.scope.context.StepContext;

class FinalizeTaskletTest {

  private static final UUID BATCH_ID = UUID.fromString("7f1c9a5e-2d3b-4c8e-9f10-1a2b3c4d5e6f");
  private static final Instant NOW = Instant.parse("2026-10-15T13:31:00Z");

  private JobExplorer explorer;
  private FileRegistryRepository registry;
  private IntakeEventPublisher publisher;
  private FinalizeTasklet tasklet;
  private FileRegistryEntry entry;
  private JobInstance instance;

  @BeforeEach
  void setUp() {
    explorer = mock(JobExplorer.class);
    registry = mock(FileRegistryRepository.class);
    publisher = mock(IntakeEventPublisher.class);
    tasklet =
        new FinalizeTasklet(
            explorer,
            registry,
            publisher,
            Clock.fixed(NOW, ZoneOffset.UTC),
            new IntakeProperties(Path.of("s"), 100, 3, Duration.ofSeconds(1)));
    entry =
        new FileRegistryEntry(
            BATCH_ID, "ACME01", "PAY_ACME01_20261015093000.csv", "h", NOW.minusSeconds(60));
    when(registry.findByBatchId(BATCH_ID.toString())).thenReturn(Optional.of(entry));
    instance = new JobInstance(1L, IngestJob.JOB_NAME);
  }

  @Test
  void allRowsAcceptedCompletesTheBatch() {
    JobExecution current = execution(2L, 20, 0, ExitStatus.COMPLETED);
    when(explorer.getJobExecutions(instance)).thenReturn(List.of(current));

    run(current);

    assertThat(entry.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    assertThat(entry.getCompletedAt()).isEqualTo(NOW);
    BatchEvent event = published(null);
    assertThat(event.status()).isEqualTo(BatchStatus.COMPLETED);
    assertThat(event.totalRows()).isEqualTo(20);
    assertThat(event.acceptedRows()).isEqualTo(20);
    assertThat(event.rejectedRows()).isZero();
    assertThat(event.controlTotal()).isEqualByComparingTo("123.45");
    assertThat(event.fileName()).isEqualTo("PAY_ACME01_20261015093000.csv");
    assertThat(current.getExecutionContext().getString(IngestJob.BATCH_STATUS))
        .isEqualTo("COMPLETED");
  }

  @Test
  void someRejectedRowsCompleteWithErrors() {
    JobExecution current = execution(2L, 17, 3, ExitStatus.COMPLETED);
    when(explorer.getJobExecutions(instance)).thenReturn(List.of(current));

    run(current);

    assertThat(published(null).status()).isEqualTo(BatchStatus.COMPLETED_WITH_ERRORS);
    assertThat(current.getExecutionContext().containsKey(IngestJob.REJECT_REASON)).isFalse();
  }

  @Test
  void countsAreSummedOverRestartedExecutions() {
    JobExecution failed = execution(1L, 5, 1, ExitStatus.FAILED);
    JobExecution current = execution(2L, 12, 2, ExitStatus.COMPLETED);
    when(explorer.getJobExecutions(instance)).thenReturn(List.of(current, failed));

    run(current);

    BatchEvent event = published(null);
    assertThat(event.acceptedRows()).isEqualTo(17);
    assertThat(event.rejectedRows()).isEqualTo(3);
    assertThat(event.status()).isEqualTo(BatchStatus.COMPLETED_WITH_ERRORS);
  }

  @Test
  void rejectedRowsOverTheSkipLimitFailTheBatchEvenAcrossRestarts() {
    JobExecution failed = execution(1L, 5, 2, ExitStatus.FAILED);
    JobExecution current = execution(2L, 11, 2, ExitStatus.COMPLETED);
    when(explorer.getJobExecutions(instance)).thenReturn(List.of(current, failed));

    run(current);

    assertThat(published(ReasonCode.SKIP_LIMIT_EXCEEDED).status()).isEqualTo(BatchStatus.FAILED);
    assertThat(entry.getReasonCode()).isEqualTo(ReasonCode.SKIP_LIMIT_EXCEEDED);
    assertThat(current.getExecutionContext().getString(IngestJob.REJECT_REASON))
        .isEqualTo("SKIP_LIMIT_EXCEEDED");
    assertThat(current.getExecutionContext().getString(IngestJob.REJECT_DETAIL))
        .contains("4 of 20");
  }

  @Test
  void stoppedBySkipLimitFailsTheBatch() {
    JobExecution current = execution(2L, 10, 3, new ExitStatus(IngestJob.SKIP_LIMIT_EXCEEDED));
    when(explorer.getJobExecutions(instance)).thenReturn(List.of(current));

    run(current);

    assertThat(published(ReasonCode.SKIP_LIMIT_EXCEEDED).status()).isEqualTo(BatchStatus.FAILED);
  }

  @Test
  void moreThanHalfRejectedFailsTheBatch() {
    JobExecution current = execution(2L, 9, 11, ExitStatus.COMPLETED);
    when(explorer.getJobExecutions(instance)).thenReturn(List.of(current));
    FinalizeTasklet generous =
        new FinalizeTasklet(
            explorer,
            registry,
            publisher,
            Clock.fixed(NOW, ZoneOffset.UTC),
            new IntakeProperties(Path.of("s"), 100, 1000, Duration.ofSeconds(1)));

    StepContribution contribution = contribution(current);
    generous.execute(
        contribution, new ChunkContext(new StepContext(contribution.getStepExecution())));

    assertThat(published(ReasonCode.REJECTION_THRESHOLD_EXCEEDED).status())
        .isEqualTo(BatchStatus.FAILED);
  }

  @Test
  void missingRegistryRowFailsTheStep() {
    JobExecution current = execution(2L, 20, 0, ExitStatus.COMPLETED);
    when(explorer.getJobExecutions(instance)).thenReturn(List.of(current));
    when(registry.findByBatchId(any())).thenReturn(Optional.empty());

    assertThatIllegalStateException().isThrownBy(() -> run(current));
  }

  private void run(JobExecution current) {
    StepContribution contribution = contribution(current);
    tasklet.execute(
        contribution, new ChunkContext(new StepContext(contribution.getStepExecution())));
  }

  private static StepContribution contribution(JobExecution current) {
    StepExecution finalize = current.createStepExecution(IngestJob.FINALIZE_STEP);
    return new StepContribution(finalize);
  }

  private JobExecution execution(long id, int written, int skipped, ExitStatus instructionExit) {
    JobParameters params =
        new JobParametersBuilder()
            .addString(IngestJob.FILE_NAME, "PAY_ACME01_20261015093000.csv")
            .addString(IngestJob.CLIENT_ID, "ACME01")
            .addString(IngestJob.FILE_HASH, "h")
            .addString(IngestJob.STAGED_PATH, "/tmp/x")
            .toJobParameters();
    JobExecution execution = new JobExecution(instance, id, params);
    execution.getExecutionContext().putString(IngestJob.BATCH_ID, BATCH_ID.toString());
    execution.getExecutionContext().putInt(IngestJob.RECORD_COUNT, 20);
    execution.getExecutionContext().putString(IngestJob.CONTROL_TOTAL, "123.45");
    StepExecution validation = execution.createStepExecution(IngestJob.FILE_VALIDATION_STEP);
    validation.setWriteCount(999);
    StepExecution instructions = execution.createStepExecution(IngestJob.INSTRUCTION_STEP);
    instructions.setWriteCount(written);
    instructions.setProcessSkipCount(skipped);
    instructions.setExitStatus(instructionExit);
    return execution;
  }

  private BatchEvent published(ReasonCode reason) {
    ArgumentCaptor<BatchEvent> captor = ArgumentCaptor.forClass(BatchEvent.class);
    if (reason == null) {
      verify(publisher).publishBatchEvent(captor.capture(), isNull());
    } else {
      verify(publisher).publishBatchEvent(captor.capture(), eq(reason));
    }
    verify(registry).saveAndFlush(entry);
    return captor.getValue();
  }
}

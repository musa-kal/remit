package ca.musakaleem.remit.intake.batch;

import static ca.musakaleem.remit.intake.support.PaymentFiles.file;
import static ca.musakaleem.remit.intake.support.PaymentFiles.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ca.musakaleem.remit.common.event.BatchEvent;
import ca.musakaleem.remit.common.event.BatchStatus;
import ca.musakaleem.remit.intake.publish.IntakeEventPublisher;
import ca.musakaleem.remit.intake.registry.FileRegistryEntry;
import ca.musakaleem.remit.intake.registry.FileRegistryRepository;
import ca.musakaleem.remit.intake.validation.ReasonCode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.scope.context.StepContext;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.batch.test.MetaDataInstanceFactory;

class FileValidationTaskletTest {

  private static final String FILE_NAME = "PAY_ACME01_20261015093000.csv";
  private static final Instant NOW = Instant.parse("2026-10-15T13:30:00Z");

  @TempDir Path dir;

  private FileRegistryRepository registry;
  private IntakeEventPublisher publisher;
  private FileValidationTasklet tasklet;

  @BeforeEach
  void setUp() {
    registry = mock(FileRegistryRepository.class);
    publisher = mock(IntakeEventPublisher.class);
    tasklet = new FileValidationTasklet(registry, publisher, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void acceptedFileGetsBatchIdRegistryRowAndReceivedEvent() throws IOException {
    Path staged = stage(file(List.of(row("A-1", "10.00"), row("A-2", "5.50"), row("A-1", "1.00"))));
    StepContribution contribution = contribution(FILE_NAME, "ACME01", staged);

    RepeatStatus status = tasklet.execute(contribution, chunkContext(contribution));

    assertThat(status).isEqualTo(RepeatStatus.FINISHED);
    ExecutionContext ctx = jobContext(contribution);
    UUID batchId = UUID.fromString(ctx.getString(IngestJob.BATCH_ID));
    assertThat(ctx.getInt(IngestJob.RECORD_COUNT)).isEqualTo(3);
    assertThat(ctx.getString(IngestJob.CONTROL_TOTAL)).isEqualTo("16.50");
    assertThat(ctx.getString(IngestJob.DUPLICATE_REF_LINES)).isEqualTo("4");
    assertThat(ctx.containsKey(IngestJob.REJECT_REASON)).isFalse();
    assertThat(contribution.getExitStatus().getExitCode()).isNotEqualTo(IngestJob.REJECTED);

    FileRegistryEntry saved = savedEntry();
    assertThat(saved.getBatchId()).isEqualTo(batchId);
    assertThat(saved.getStatus()).isEqualTo(BatchStatus.RECEIVED);
    assertThat(saved.getReceivedAt()).isEqualTo(NOW);

    BatchEvent event = publishedEvent(null);
    assertThat(event.status()).isEqualTo(BatchStatus.RECEIVED);
    assertThat(event.batchId()).isEqualTo(batchId);
    assertThat(event.totalRows()).isEqualTo(3);
    assertThat(event.controlTotal()).isEqualByComparingTo("16.50");
    assertThat(event.fileHash()).isEqualTo(FileHashing.sha256(staged));
  }

  @Test
  void invalidFileNameIsRejectedWithoutRegistryRowOrEvent() throws IOException {
    Path staged = stage(file(List.of(row("A-1", "1.00"))));
    StepContribution contribution = contribution("payments.csv", "ACME01", staged);

    tasklet.execute(contribution, chunkContext(contribution));

    assertRejected(contribution, ReasonCode.INVALID_FILENAME);
    verifyNoInteractions(registry, publisher);
  }

  @Test
  void clientIdParameterMustMatchTheFileName() throws IOException {
    Path staged = stage(file(List.of(row("A-1", "1.00"))));
    StepContribution contribution = contribution(FILE_NAME, "BETA02", staged);

    tasklet.execute(contribution, chunkContext(contribution));

    assertRejected(contribution, ReasonCode.INVALID_FILENAME);
  }

  @Test
  void changedStagedFileFailsTheStep() throws IOException {
    Path staged = stage(file(List.of(row("A-1", "1.00"))));
    StepContribution contribution = contribution(FILE_NAME, "ACME01", staged);
    Files.writeString(staged, "tampered");

    assertThatIllegalStateException()
        .isThrownBy(() -> tasklet.execute(contribution, chunkContext(contribution)))
        .withMessageContaining("no longer matches");
  }

  @Test
  void duplicateFileIsRejectedWithAFailedEventAndNoRegistryRow() throws IOException {
    Path staged = stage(file(List.of(row("A-1", "1.00"))));
    when(registry.existsByFileHash(FileHashing.sha256(staged))).thenReturn(true);
    StepContribution contribution = contribution(FILE_NAME, "ACME01", staged);

    tasklet.execute(contribution, chunkContext(contribution));

    assertRejected(contribution, ReasonCode.DUPLICATE_FILE);
    verify(registry, never()).saveAndFlush(any());
    BatchEvent event = publishedEvent(ReasonCode.DUPLICATE_FILE);
    assertThat(event.status()).isEqualTo(BatchStatus.FAILED);
    assertThat(event.totalRows()).isZero();
  }

  @Test
  void contentFailureRegistersAFailedFileAndPublishesFailed() throws IOException {
    Path staged = stage(file(List.of(row("A-1", "1.00"), row("A-2", "2.00")), "TRAILER,2,9.99"));
    StepContribution contribution = contribution(FILE_NAME, "ACME01", staged);

    tasklet.execute(contribution, chunkContext(contribution));

    assertRejected(contribution, ReasonCode.CONTROL_TOTAL_MISMATCH);
    FileRegistryEntry saved = savedEntry();
    assertThat(saved.getStatus()).isEqualTo(BatchStatus.FAILED);
    assertThat(saved.getReasonCode()).isEqualTo(ReasonCode.CONTROL_TOTAL_MISMATCH);
    assertThat(saved.getCompletedAt()).isEqualTo(NOW);
    BatchEvent event = publishedEvent(ReasonCode.CONTROL_TOTAL_MISMATCH);
    assertThat(event.batchId()).isEqualTo(saved.getBatchId());
    assertThat(event.totalRows()).isEqualTo(2);
    assertThat(event.controlTotal()).isEqualByComparingTo("9.99");
  }

  @Test
  void invalidUtf8IsRejected() throws IOException {
    Path staged = dir.resolve(FILE_NAME);
    Files.write(staged, new byte[] {'a', (byte) 0xC3, (byte) 0x28, '\n'});
    StepContribution contribution = contribution(FILE_NAME, "ACME01", staged);

    tasklet.execute(contribution, chunkContext(contribution));

    assertRejected(contribution, ReasonCode.INVALID_ENCODING);
  }

  private Path stage(String content) throws IOException {
    Path staged = dir.resolve(FILE_NAME);
    Files.writeString(staged, content, StandardCharsets.UTF_8);
    return staged;
  }

  private static StepContribution contribution(String fileName, String clientId, Path staged) {
    JobParameters params =
        new JobParametersBuilder()
            .addString(IngestJob.FILE_NAME, fileName)
            .addString(IngestJob.CLIENT_ID, clientId)
            .addString(IngestJob.FILE_HASH, FileHashing.sha256(staged))
            .addString(IngestJob.STAGED_PATH, staged.toString())
            .toJobParameters();
    StepExecution stepExecution = MetaDataInstanceFactory.createStepExecution(params);
    return new StepContribution(stepExecution);
  }

  private static ChunkContext chunkContext(StepContribution contribution) {
    return new ChunkContext(new StepContext(contribution.getStepExecution()));
  }

  private static ExecutionContext jobContext(StepContribution contribution) {
    return contribution.getStepExecution().getJobExecution().getExecutionContext();
  }

  private static void assertRejected(StepContribution contribution, ReasonCode reason) {
    assertThat(contribution.getExitStatus().getExitCode()).isEqualTo(IngestJob.REJECTED);
    ExecutionContext ctx = jobContext(contribution);
    assertThat(ctx.getString(IngestJob.REJECT_REASON)).isEqualTo(reason.name());
    assertThat(ctx.getString(IngestJob.REJECT_DETAIL)).isNotBlank();
    assertThat(ctx.getString(IngestJob.BATCH_STATUS)).isEqualTo("FAILED");
  }

  private FileRegistryEntry savedEntry() {
    ArgumentCaptor<FileRegistryEntry> captor = ArgumentCaptor.forClass(FileRegistryEntry.class);
    verify(registry).saveAndFlush(captor.capture());
    return captor.getValue();
  }

  private BatchEvent publishedEvent(ReasonCode reason) {
    ArgumentCaptor<BatchEvent> captor = ArgumentCaptor.forClass(BatchEvent.class);
    if (reason == null) {
      verify(publisher).publishBatchEvent(captor.capture(), isNull());
    } else {
      verify(publisher).publishBatchEvent(captor.capture(), eq(reason));
    }
    return captor.getValue();
  }
}

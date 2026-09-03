package ca.musakaleem.remit.intake.batch;

import ca.musakaleem.remit.common.event.BatchEvent;
import ca.musakaleem.remit.common.event.BatchStatus;
import ca.musakaleem.remit.intake.logging.MdcKeys;
import ca.musakaleem.remit.intake.publish.IntakeEventPublisher;
import ca.musakaleem.remit.intake.registry.FileRegistryEntry;
import ca.musakaleem.remit.intake.registry.FileRegistryRepository;
import ca.musakaleem.remit.intake.validation.BatchFileName;
import ca.musakaleem.remit.intake.validation.FileCheck;
import ca.musakaleem.remit.intake.validation.PaymentFileValidator;
import ca.musakaleem.remit.intake.validation.ReasonCode;
import ca.musakaleem.remit.intake.validation.ScannedFile;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.repeat.RepeatStatus;

/**
 * {@code fileValidationStep}: file name, duplicate file, header, trailer, zero rows, record count
 * and control total (spec 3.4). An accepted file gets a batch id, a {@code RECEIVED} registry row
 * and a {@code RECEIVED} batch event. A rejected file ends the job with exit code {@code REJECTED}.
 */
public class FileValidationTasklet implements Tasklet {

  private static final Logger log = LoggerFactory.getLogger(FileValidationTasklet.class);

  private final FileRegistryRepository registry;
  private final IntakeEventPublisher publisher;
  private final Clock clock;

  /**
   * Creates the tasklet.
   *
   * @param registry file registry, whose unique file hash guards against duplicates
   * @param publisher event publisher
   * @param clock clock for receive timestamps
   */
  public FileValidationTasklet(
      FileRegistryRepository registry, IntakeEventPublisher publisher, Clock clock) {
    this.registry = registry;
    this.publisher = publisher;
    this.clock = clock;
  }

  @Override
  public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
    JobParameters params = contribution.getStepExecution().getJobParameters();
    ExecutionContext jobContext =
        contribution.getStepExecution().getJobExecution().getExecutionContext();
    String fileName = params.getString(IngestJob.FILE_NAME);
    String clientId = params.getString(IngestJob.CLIENT_ID);
    String fileHash = params.getString(IngestJob.FILE_HASH);
    Path stagedPath = Path.of(params.getString(IngestJob.STAGED_PATH));
    Instant now = clock.instant();

    Optional<BatchFileName> name = BatchFileName.parse(fileName);
    if (name.isEmpty() || !name.get().clientId().equals(clientId)) {
      reject(
          contribution,
          jobContext,
          ReasonCode.INVALID_FILENAME,
          "file name does not match PAY_<CLIENTID>_<yyyyMMddHHmmss>.csv for client " + clientId);
      return RepeatStatus.FINISHED;
    }
    String actualHash = FileHashing.sha256(stagedPath);
    if (!actualHash.equals(fileHash)) {
      throw new IllegalStateException(
          "Staged file " + stagedPath + " no longer matches job parameter fileHash " + fileHash);
    }

    if (registry.existsByFileHash(fileHash)) {
      UUID attemptId = UUID.randomUUID();
      MDC.put(MdcKeys.CORRELATION_ID, attemptId.toString());
      publisher.publishBatchEvent(
          new BatchEvent(
              attemptId, clientId, fileName, fileHash, BatchStatus.FAILED, 0, 0, 0, null, now),
          ReasonCode.DUPLICATE_FILE);
      reject(
          contribution,
          jobContext,
          ReasonCode.DUPLICATE_FILE,
          "a file with the same SHA-256 was already received");
      return RepeatStatus.FINISHED;
    }

    FileCheck check = checkContent(stagedPath);
    UUID batchId = UUID.randomUUID();
    MDC.put(MdcKeys.CORRELATION_ID, batchId.toString());
    jobContext.putString(IngestJob.BATCH_ID, batchId.toString());
    FileRegistryEntry entry = new FileRegistryEntry(batchId, clientId, fileName, fileHash, now);

    if (!check.isAccepted()) {
      entry.complete(BatchStatus.FAILED, check.rejection(), now);
      registry.saveAndFlush(entry);
      publisher.publishBatchEvent(
          new BatchEvent(
              batchId,
              clientId,
              fileName,
              fileHash,
              BatchStatus.FAILED,
              check.rowCount(),
              0,
              0,
              check.declaredTotal(),
              now),
          check.rejection());
      reject(contribution, jobContext, check.rejection(), check.detail());
      return RepeatStatus.FINISHED;
    }

    registry.saveAndFlush(entry);
    BigDecimal controlTotal = check.declaredTotal();
    jobContext.putInt(IngestJob.RECORD_COUNT, check.rowCount());
    jobContext.putString(IngestJob.CONTROL_TOTAL, controlTotal.toPlainString());
    jobContext.putString(
        IngestJob.DUPLICATE_REF_LINES,
        check.duplicateRefLines().stream().map(String::valueOf).collect(Collectors.joining(",")));
    publisher.publishBatchEvent(
        new BatchEvent(
            batchId,
            clientId,
            fileName,
            fileHash,
            BatchStatus.RECEIVED,
            check.rowCount(),
            0,
            0,
            controlTotal,
            now),
        null);
    log.info(
        "Accepted file {} with {} rows, control total {}",
        fileName,
        check.rowCount(),
        controlTotal.toPlainString());
    return RepeatStatus.FINISHED;
  }

  private static FileCheck checkContent(Path stagedPath) {
    try (BufferedReader reader = Files.newBufferedReader(stagedPath, StandardCharsets.UTF_8)) {
      return PaymentFileValidator.check(ScannedFile.scan(reader));
    } catch (CharacterCodingException e) {
      return FileCheck.rejected(ReasonCode.INVALID_ENCODING, "file is not valid UTF-8", 0, null);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read staged file " + stagedPath, e);
    }
  }

  private static void reject(
      StepContribution contribution,
      ExecutionContext jobContext,
      ReasonCode reason,
      String detail) {
    jobContext.putString(IngestJob.BATCH_STATUS, BatchStatus.FAILED.name());
    jobContext.putString(IngestJob.REJECT_REASON, reason.name());
    jobContext.putString(IngestJob.REJECT_DETAIL, detail);
    contribution.setExitStatus(new ExitStatus(IngestJob.REJECTED, reason.name()));
    log.warn("Rejected file: {} ({})", reason, detail);
  }
}

package ca.musakaleem.remit.intake.ingest;

import ca.musakaleem.remit.intake.config.SftpProperties;
import ca.musakaleem.remit.intake.logging.MdcKeys;
import ca.musakaleem.remit.intake.sftp.SftpFileGateway;
import ca.musakaleem.remit.intake.validation.BatchFileName;
import ca.musakaleem.remit.intake.validation.ReasonCode;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * Takes one file from {@code /inbound} through the job (FR-INT-02): stage it locally, move it to
 * {@code /processing}, run the job, then move it to {@code /processed} or to {@code /error} with a
 * {@code <file>.error.txt} note. A technically failed job leaves the file in {@code /processing}.
 */
@Component
public class InboundFileProcessor {

  /** Suffix of the note written next to a rejected file. */
  public static final String ERROR_NOTE_SUFFIX = ".error.txt";

  private static final Logger log = LoggerFactory.getLogger(InboundFileProcessor.class);

  private final SftpFileGateway gateway;
  private final StagingArea staging;
  private final IngestJobService jobs;
  private final SftpProperties sftp;

  /**
   * Creates the processor.
   *
   * @param gateway SFTP operations
   * @param staging local staging area
   * @param jobs job launcher facade
   * @param sftp remote directory names
   */
  public InboundFileProcessor(
      SftpFileGateway gateway, StagingArea staging, IngestJobService jobs, SftpProperties sftp) {
    this.gateway = gateway;
    this.staging = staging;
    this.jobs = jobs;
    this.sftp = sftp;
  }

  /**
   * Processes one file found in the inbound directory.
   *
   * @param fileName remote file name
   */
  public void process(String fileName) {
    MDC.put(MdcKeys.FILE_NAME, fileName);
    try {
      Optional<BatchFileName> name = BatchFileName.parse(fileName);
      if (name.isEmpty()) {
        log.warn("Rejecting {}: name does not match PAY_<CLIENTID>_<yyyyMMddHHmmss>.csv", fileName);
        gateway.move(sftp.inboundDir(), sftp.errorDir(), fileName);
        writeErrorNote(
            fileName,
            ReasonCode.INVALID_FILENAME.name(),
            "file name does not match PAY_<CLIENTID>_<yyyyMMddHHmmss>.csv");
        return;
      }
      StagedFile staged =
          staging.stage(fileName, target -> gateway.download(sftp.inboundDir(), fileName, target));
      gateway.move(sftp.inboundDir(), sftp.processingDir(), fileName);
      log.info("Staged {} ({}), starting ingest job", fileName, staged.fileHash());
      complete(jobs.launch(staged, name.get().clientId()));
    } finally {
      MDC.remove(MdcKeys.FILE_NAME);
    }
  }

  /**
   * Moves the remote file out of {@code /processing} according to the job outcome and discards the
   * staged copy, unless the job has to be restarted.
   *
   * @param outcome outcome of the job
   */
  public void complete(JobOutcome outcome) {
    String fileName = outcome.fileName();
    switch (outcome.disposition()) {
      case PROCESSED -> {
        gateway.move(sftp.processingDir(), sftp.processedDir(), fileName);
        staging.discard(outcome.stagedPath());
        log.info("File {} processed (execution {})", fileName, outcome.executionId());
      }
      case REJECTED -> {
        gateway.move(sftp.processingDir(), sftp.errorDir(), fileName);
        writeErrorNote(fileName, outcome.reasonCode(), outcome.detail());
        staging.discard(outcome.stagedPath());
        log.warn("File {} moved to error: {}", fileName, outcome.reasonCode());
      }
      case INCOMPLETE ->
          log.error(
              "Job for {} did not complete (execution {}: {}); file left in {} for a restart,"
                  + " see docs/runbook.md",
              fileName,
              outcome.executionId(),
              outcome.detail(),
              sftp.processingDir());
    }
  }

  private void writeErrorNote(String fileName, String reasonCode, String detail) {
    JobOutcome note =
        new JobOutcome(null, fileName, null, JobOutcome.Disposition.REJECTED, reasonCode, detail);
    gateway.writeText(sftp.errorDir(), fileName + ERROR_NOTE_SUFFIX, note.errorNote());
  }
}

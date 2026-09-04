package ca.musakaleem.remit.intake.ingest;

import ca.musakaleem.remit.intake.config.SftpProperties;
import ca.musakaleem.remit.intake.sftp.SftpFileGateway;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Polls the SFTP {@code /inbound} directory every {@code remit.sftp.poll-interval} (FR-INT-01) and
 * processes the files one at a time. A failure on one file is logged and does not stop the others;
 * a failed poll is retried at the next interval.
 */
@Component
@ConditionalOnProperty(
    prefix = "remit.sftp",
    name = "poller-enabled",
    havingValue = "true",
    matchIfMissing = true)
public class SftpPoller {

  private static final Logger log = LoggerFactory.getLogger(SftpPoller.class);

  private final SftpFileGateway gateway;
  private final InboundFileProcessor processor;
  private final SftpProperties sftp;

  /**
   * Creates the poller.
   *
   * @param gateway SFTP operations
   * @param processor per-file workflow
   * @param sftp directory names
   */
  public SftpPoller(SftpFileGateway gateway, InboundFileProcessor processor, SftpProperties sftp) {
    this.gateway = gateway;
    this.processor = processor;
    this.sftp = sftp;
  }

  /** Lists the inbound directory and processes every CSV file found. */
  @Scheduled(
      fixedDelayString = "${remit.sftp.poll-interval:30s}",
      initialDelayString = "${remit.sftp.initial-delay:5s}")
  public void poll() {
    List<String> files;
    try {
      files = gateway.listCsvFiles(sftp.inboundDir());
    } catch (RuntimeException e) {
      log.warn("SFTP poll of {} failed: {}", sftp.inboundDir(), e.getMessage());
      return;
    }
    if (!files.isEmpty()) {
      log.info("Found {} file(s) in {}", files.size(), sftp.inboundDir());
    }
    for (String file : files) {
      try {
        processor.process(file);
      } catch (RuntimeException e) {
        log.error("Processing {} failed; it will be retried on a later poll", file, e);
      }
    }
  }
}

package ca.musakaleem.remit.intake;

import static ca.musakaleem.remit.intake.support.IntakeContainers.SFTP;
import static ca.musakaleem.remit.intake.support.IntakeContainers.SFTP_HOME;
import static ca.musakaleem.remit.intake.support.IntakeContainers.sftpFiles;
import static ca.musakaleem.remit.intake.support.IntakeContainers.sftpRead;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ca.musakaleem.remit.common.Topics;
import ca.musakaleem.remit.common.event.BatchEvent;
import ca.musakaleem.remit.common.event.BatchStatus;
import ca.musakaleem.remit.intake.registry.FileRegistryEntry;
import ca.musakaleem.remit.intake.support.AbstractIntakeIT;
import ca.musakaleem.remit.intake.support.PaymentFiles;
import ca.musakaleem.remit.intake.support.TopicReader;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.images.builder.Transferable;

/**
 * The scheduled poller against a real atmoz/sftp container (FR-INT-01, FR-INT-02): files dropped in
 * /inbound end up in /processed or in /error with a note.
 */
@TestPropertySource(
    properties = {
      "remit.sftp.poller-enabled=true",
      "remit.sftp.poll-interval=1s",
      "remit.sftp.initial-delay=1s"
    })
class SftpPollerIT extends AbstractIntakeIT {

  private static final Duration TIMEOUT = Duration.ofSeconds(60);

  @Test
  void validFileIsProcessedAndItsEventsPublished() {
    String name = "PAY_POLL01_20261015140000.csv";
    upload(name, PaymentFiles.file(PaymentFiles.validRows("POLL01", 3)));

    await().atMost(TIMEOUT).until(() -> sftpFiles("processed").contains(name));

    assertThat(sftpFiles("inbound")).doesNotContain(name);
    assertThat(sftpFiles("processing")).doesNotContain(name);
    FileRegistryEntry entry = registryEntry(name);
    assertThat(entry.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    String batchId = entry.getBatchId().toString();
    assertThat(TopicReader.read(Topics.INSTRUCTIONS, batchId)).hasSize(3);
    List<BatchEvent> events =
        TopicReader.read(Topics.BATCH_EVENTS, batchId).stream()
            .map(r -> (BatchEvent) r.value())
            .toList();
    assertThat(events)
        .extracting(BatchEvent::status)
        .containsExactly(BatchStatus.RECEIVED, BatchStatus.COMPLETED);
    assertThat(events.get(1).acceptedRows()).isEqualTo(3);
  }

  @Test
  void badlyNamedFileGoesToErrorWithAReasonNote() {
    String name = "payments-today.csv";
    upload(name, PaymentFiles.file(PaymentFiles.validRows("X", 1)));

    await().atMost(TIMEOUT).until(() -> sftpFiles("error").contains(name + ".error.txt"));

    assertThat(sftpFiles("error")).contains(name);
    assertThat(sftpRead("error/" + name + ".error.txt")).startsWith("INVALID_FILENAME\n");
  }

  @Test
  void fileLevelFailureGoesToErrorWithItsReasonCode() {
    String name = "PAY_POLL01_20261015150000.csv";
    upload(name, PaymentFiles.file(PaymentFiles.validRows("POLL01-B", 2), "TRAILER,2,0.01"));

    await().atMost(TIMEOUT).until(() -> sftpFiles("error").contains(name + ".error.txt"));

    assertThat(sftpFiles("error")).contains(name);
    assertThat(sftpFiles("processing")).doesNotContain(name);
    assertThat(sftpRead("error/" + name + ".error.txt"))
        .startsWith("CONTROL_TOTAL_MISMATCH\n")
        .contains("0.01");
    FileRegistryEntry entry = registryEntry(name);
    assertThat(entry.getStatus()).isEqualTo(BatchStatus.FAILED);
  }

  private static void upload(String name, String content) {
    SFTP.copyFileToContainer(Transferable.of(content), SFTP_HOME + "/inbound/" + name);
  }

  private FileRegistryEntry registryEntry(String fileName) {
    return registry.findAll().stream()
        .filter(e -> e.getFileName().equals(fileName))
        .findFirst()
        .orElseThrow();
  }
}

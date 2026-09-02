package ca.musakaleem.remit.intake.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import ca.musakaleem.remit.common.event.BatchStatus;
import ca.musakaleem.remit.intake.validation.ReasonCode;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FileRegistryEntryTest {

  private static final UUID BATCH_ID = UUID.fromString("7f1c9a5e-2d3b-4c8e-9f10-1a2b3c4d5e6f");
  private static final Instant RECEIVED = Instant.parse("2026-10-15T13:30:00Z");

  @Test
  void startsAsReceived() {
    FileRegistryEntry entry =
        new FileRegistryEntry(
            BATCH_ID, "ACME01", "PAY_ACME01_20261015093000.csv", "a".repeat(64), RECEIVED);

    assertThat(entry.getId()).isNull();
    assertThat(entry.getBatchId()).isEqualTo(BATCH_ID);
    assertThat(entry.getClientId()).isEqualTo("ACME01");
    assertThat(entry.getFileName()).isEqualTo("PAY_ACME01_20261015093000.csv");
    assertThat(entry.getFileHash()).hasSize(64);
    assertThat(entry.getStatus()).isEqualTo(BatchStatus.RECEIVED);
    assertThat(entry.getReceivedAt()).isEqualTo(RECEIVED);
    assertThat(entry.getReasonCode()).isNull();
    assertThat(entry.getCompletedAt()).isNull();
  }

  @Test
  void completeRecordsStatusReasonAndTime() {
    FileRegistryEntry entry = new FileRegistryEntry(BATCH_ID, "ACME01", "f.csv", "h", RECEIVED);
    Instant done = RECEIVED.plusSeconds(3);

    entry.complete(BatchStatus.FAILED, ReasonCode.CONTROL_TOTAL_MISMATCH, done);

    assertThat(entry.getStatus()).isEqualTo(BatchStatus.FAILED);
    assertThat(entry.getReasonCode()).isEqualTo(ReasonCode.CONTROL_TOTAL_MISMATCH);
    assertThat(entry.getCompletedAt()).isEqualTo(done);
  }

  @Test
  void receivedIsNotAFinalStatus() {
    FileRegistryEntry entry = new FileRegistryEntry(BATCH_ID, "ACME01", "f.csv", "h", RECEIVED);

    assertThatIllegalArgumentException()
        .isThrownBy(() -> entry.complete(BatchStatus.RECEIVED, null, RECEIVED));
  }
}

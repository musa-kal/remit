package ca.musakaleem.remit.api.batch;

import static org.assertj.core.api.Assertions.assertThat;

import ca.musakaleem.remit.api.support.TestEvents;
import ca.musakaleem.remit.common.event.BatchEvent;
import ca.musakaleem.remit.common.event.BatchStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentBatchTest {

  private static final Instant T0 = Instant.parse("2026-10-15T13:30:00Z");
  private static final UUID BATCH = UUID.randomUUID();

  @Test
  void aReceivedEventCreatesTheBatch() {
    PaymentBatch batch = PaymentBatch.from(TestEvents.batch(BATCH, BatchStatus.RECEIVED, T0), null);

    assertThat(batch.getBatchId()).isEqualTo(BATCH);
    assertThat(batch.getStatus()).isEqualTo(BatchStatus.RECEIVED);
    assertThat(batch.getTotalRows()).isEqualTo(10);
    assertThat(batch.getAcceptedRows()).isZero();
    assertThat(batch.getControlTotal()).isEqualByComparingTo("12500.00");
    assertThat(batch.getReceivedAt()).isEqualTo(T0);
    assertThat(batch.getCompletedAt()).isNull();
    assertThat(batch.getUpdatedAt()).isEqualTo(T0);
    assertThat(batch.getFileName()).isEqualTo("PAY_TEST01_20261015093000.csv");
    assertThat(batch.getClientId()).isEqualTo("TEST01");
    assertThat(batch.getFileHash()).hasSize(64);
  }

  @Test
  void aLaterCompletedEventUpdatesCountsAndCompletionTime() {
    PaymentBatch batch = PaymentBatch.from(TestEvents.batch(BATCH, BatchStatus.RECEIVED, T0), null);
    Instant later = T0.plusSeconds(5);

    boolean applied =
        batch.apply(TestEvents.batch(BATCH, BatchStatus.COMPLETED_WITH_ERRORS, later), null);

    assertThat(applied).isTrue();
    assertThat(batch.getStatus()).isEqualTo(BatchStatus.COMPLETED_WITH_ERRORS);
    assertThat(batch.getAcceptedRows()).isEqualTo(8);
    assertThat(batch.getRejectedRows()).isEqualTo(2);
    assertThat(batch.getReceivedAt()).isEqualTo(T0);
    assertThat(batch.getCompletedAt()).isEqualTo(later);
    assertThat(batch.getUpdatedAt()).isEqualTo(later);
    assertThat(batch.getFailureReason()).isNull();
  }

  @Test
  void anOlderEventIsIgnored() {
    PaymentBatch batch =
        PaymentBatch.from(TestEvents.batch(BATCH, BatchStatus.COMPLETED, T0.plusSeconds(5)), null);

    boolean applied = batch.apply(TestEvents.batch(BATCH, BatchStatus.RECEIVED, T0), null);

    assertThat(applied).isFalse();
    assertThat(batch.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    assertThat(batch.getAcceptedRows()).isEqualTo(8);
  }

  @Test
  void aRedeliveredEventWithTheSameTimeIsAppliedAgainHarmlessly() {
    BatchEvent completed = TestEvents.batch(BATCH, BatchStatus.COMPLETED, T0);
    PaymentBatch batch = PaymentBatch.from(completed, null);

    assertThat(batch.apply(completed, null)).isTrue();
    assertThat(batch.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    assertThat(batch.getUpdatedAt()).isEqualTo(T0);
  }

  @Test
  void aFinalStatusNeverGoesBackToReceivedEvenAtTheSameTime() {
    PaymentBatch batch = PaymentBatch.from(TestEvents.batch(BATCH, BatchStatus.FAILED, T0), "X");

    assertThat(batch.apply(TestEvents.batch(BATCH, BatchStatus.RECEIVED, T0), null)).isFalse();
    assertThat(batch.getStatus()).isEqualTo(BatchStatus.FAILED);
  }

  @Test
  void aFileRejectedAsAWholeIsCreatedFailedWithItsReason() {
    BatchEvent failed =
        new BatchEvent(
            BATCH,
            "TEST01",
            "PAY_TEST01_20261015110000.csv",
            "b".repeat(64),
            BatchStatus.FAILED,
            0,
            0,
            0,
            null,
            T0);

    PaymentBatch batch = PaymentBatch.from(failed, "DUPLICATE_FILE");

    assertThat(batch.getStatus()).isEqualTo(BatchStatus.FAILED);
    assertThat(batch.getFailureReason()).isEqualTo("DUPLICATE_FILE");
    assertThat(batch.getReceivedAt()).isEqualTo(T0);
    assertThat(batch.getCompletedAt()).isEqualTo(T0);
    assertThat(batch.getControlTotal()).isNull();
  }

  @Test
  void aMissingControlTotalKeepsTheKnownOne() {
    PaymentBatch batch = PaymentBatch.from(TestEvents.batch(BATCH, BatchStatus.RECEIVED, T0), null);
    BatchEvent withoutTotal =
        new BatchEvent(
            BATCH,
            "TEST01",
            "PAY_TEST01_20261015093000.csv",
            "a".repeat(64),
            BatchStatus.COMPLETED,
            10,
            10,
            0,
            null,
            T0.plusSeconds(1));

    batch.apply(withoutTotal, null);

    assertThat(batch.getControlTotal()).isEqualByComparingTo(new BigDecimal("12500.00"));
  }
}

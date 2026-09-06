package ca.musakaleem.remit.routing.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import ca.musakaleem.remit.common.event.Channel;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProcessedInstructionTest {

  private static final String KEY = "a".repeat(64);
  private static final UUID BATCH_ID = UUID.fromString("7f1c9a5e-2d3b-4c8e-9f10-1a2b3c4d5e6f");
  private static final Instant T0 = Instant.parse("2026-10-15T13:30:00.123Z");
  private static final Instant T1 = T0.plusSeconds(1);

  @Test
  void aNewRowClaimsTheKeyInRoutingWithOneAttempt() {
    ProcessedInstruction row = newRow();

    assertThat(row.getIdempotencyKey()).isEqualTo(KEY);
    assertThat(row.getBatchId()).isEqualTo(BATCH_ID);
    assertThat(row.getChannel()).isEqualTo(Channel.WIRE);
    assertThat(row.getAmount()).isEqualByComparingTo("1500000.00");
    assertThat(row.getStatus()).isEqualTo(ProcessingStatus.ROUTING);
    assertThat(row.getAttempts()).isEqualTo(1);
    assertThat(row.getCreatedAt()).isEqualTo(T0);
    assertThat(row.getUpdatedAt()).isEqualTo(T0);
    assertThat(row.getVersion()).as("null until inserted, so Spring Data persists it").isNull();
  }

  @Test
  void recordAttemptCountsRetriesOfARoutingRow() {
    ProcessedInstruction row = newRow();

    row.recordAttempt(T1);
    row.recordAttempt(T1);

    assertThat(row.getAttempts()).isEqualTo(3);
    assertThat(row.getUpdatedAt()).isEqualTo(T1);
    assertThat(row.getCreatedAt()).isEqualTo(T0);
  }

  @Test
  void finishRecordsTheFinalStatus() {
    ProcessedInstruction row = newRow();

    row.finish(ProcessingStatus.REQUIRES_REVIEW, T1);

    assertThat(row.getStatus()).isEqualTo(ProcessingStatus.REQUIRES_REVIEW);
    assertThat(row.getUpdatedAt()).isEqualTo(T1);
  }

  @Test
  void aFinishedRowCannotStartAnotherAttempt() {
    ProcessedInstruction row = newRow();
    row.finish(ProcessingStatus.ROUTED, T1);

    assertThatIllegalStateException()
        .isThrownBy(() -> row.recordAttempt(T1))
        .withMessageContaining("already ROUTED");
  }

  @Test
  void routingIsNotAFinalStatus() {
    ProcessedInstruction row = newRow();

    assertThatIllegalArgumentException().isThrownBy(() -> row.finish(ProcessingStatus.ROUTING, T1));
  }

  @Test
  void requiresEveryField() {
    BigDecimal amount = BigDecimal.ONE;
    assertThatNullPointerException()
        .isThrownBy(() -> new ProcessedInstruction(null, BATCH_ID, Channel.EFT, amount, T0));
    assertThatNullPointerException()
        .isThrownBy(() -> new ProcessedInstruction(KEY, null, Channel.EFT, amount, T0));
    assertThatNullPointerException()
        .isThrownBy(() -> new ProcessedInstruction(KEY, BATCH_ID, null, amount, T0));
    assertThatNullPointerException()
        .isThrownBy(() -> new ProcessedInstruction(KEY, BATCH_ID, Channel.EFT, null, T0));
    assertThatNullPointerException()
        .isThrownBy(() -> new ProcessedInstruction(KEY, BATCH_ID, Channel.EFT, amount, null));
  }

  @Test
  void onlyRoutingIsNotFinal() {
    assertThat(ProcessingStatus.ROUTING.isFinal()).isFalse();
    assertThat(ProcessingStatus.ROUTED.isFinal()).isTrue();
    assertThat(ProcessingStatus.REQUIRES_REVIEW.isFinal()).isTrue();
  }

  private static ProcessedInstruction newRow() {
    return new ProcessedInstruction(KEY, BATCH_ID, Channel.WIRE, new BigDecimal("1500000.00"), T0);
  }
}

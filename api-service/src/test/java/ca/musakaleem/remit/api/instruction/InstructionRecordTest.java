package ca.musakaleem.remit.api.instruction;

import static org.assertj.core.api.Assertions.assertThat;

import ca.musakaleem.remit.api.support.TestEvents;
import ca.musakaleem.remit.common.event.Channel;
import ca.musakaleem.remit.common.event.InstructionStatus;
import ca.musakaleem.remit.common.event.PaymentInstruction;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InstructionRecordTest {

  private static final Instant T0 = Instant.parse("2026-10-15T13:30:00Z");

  private final PaymentInstruction instruction = TestEvents.instruction(UUID.randomUUID(), 0);

  @Test
  void aNewerStatusReplacesTheCurrentOne() {
    InstructionRecord row = routed(T0);

    boolean applied =
        row.applyStatus(InstructionStatus.DEAD_LETTERED, null, "boom", T0.plusSeconds(1));

    assertThat(applied).isTrue();
    assertThat(row.getStatus()).isEqualTo(InstructionStatus.DEAD_LETTERED);
    assertThat(row.getStatusDetail()).isEqualTo("boom");
    assertThat(row.getChannel()).isEqualTo(Channel.EFT);
    assertThat(row.getUpdatedAt()).isEqualTo(T0.plusSeconds(1));
  }

  @Test
  void aStrictlyOlderStatusIsIgnored() {
    InstructionRecord row = routed(T0);

    assertThat(row.applyStatus(InstructionStatus.DEAD_LETTERED, null, "late", T0.minusMillis(1)))
        .isFalse();
    assertThat(row.getStatus()).isEqualTo(InstructionStatus.ROUTED);
  }

  @Test
  void replayedThenRoutedInTheSameMillisecondEndsRouted() {
    InstructionRecord row =
        new InstructionRecord(
            instruction.idempotencyKey(),
            instruction.batchId(),
            InstructionStatus.DEAD_LETTERED,
            Channel.EFT,
            "boom",
            T0);
    Instant replayed = T0.plusSeconds(30);

    assertThat(row.applyStatus(InstructionStatus.REPLAYED, Channel.EFT, "replay 1", replayed))
        .isTrue();
    assertThat(row.applyStatus(InstructionStatus.ROUTED, Channel.EFT, null, replayed)).isTrue();

    assertThat(row.getStatus()).isEqualTo(InstructionStatus.ROUTED);
    assertThat(row.getStatusDetail()).isNull();
  }

  @Test
  void eventsWithinTheSameMillisecondAreComparedAtStoredPrecision() {
    InstructionRecord row = routed(Instant.parse("2026-10-15T13:30:00.1236Z"));

    boolean applied =
        row.applyStatus(
            InstructionStatus.REQUIRES_REVIEW,
            Channel.WIRE,
            null,
            Instant.parse("2026-10-15T13:30:00.1231Z"));

    assertThat(applied).isTrue();
    assertThat(row.getUpdatedAt()).isEqualTo(Instant.parse("2026-10-15T13:30:00.123Z"));
  }

  @Test
  void aDuplicateNeverDowngradesARoutedInstruction() {
    InstructionRecord row = routed(T0);

    assertThat(
            row.applyStatus(
                InstructionStatus.DUPLICATE, Channel.EFT, "already ROUTED", T0.plusSeconds(60)))
        .isFalse();
    assertThat(row.getStatus()).isEqualTo(InstructionStatus.ROUTED);
    assertThat(row.getUpdatedAt()).isEqualTo(T0);
  }

  @Test
  void detailsAreCopiedAndTheReplayCountOnlyGrows() {
    InstructionRecord row = routed(T0);
    row.applyDetails(TestEvents.instruction(instruction.batchId(), 2));
    row.applyDetails(instruction);

    assertThat(row.getReplayCount()).isEqualTo(2);
    assertThat(row.getDebtor().account()).isEqualTo(TestEvents.DEBTOR_ACCOUNT);
    assertThat(row.getCreditor().name()).isEqualTo("Test Creditor Inc");
    assertThat(row.getAmount()).isEqualByComparingTo("1250.00");
    assertThat(row.getCurrency()).isEqualTo("CAD");
    assertThat(row.getValueDate()).isEqualTo(instruction.valueDate());
    assertThat(row.getRemittanceInfo()).isEqualTo("Invoice 1001");
    assertThat(row.getClientId()).isEqualTo("TEST01");
    assertThat(row.getInstructionRef()).isEqualTo(instruction.instructionRef());
  }

  @Test
  void partiesAreUnknownUntilTheDetailsArrive() {
    InstructionRecord row =
        new InstructionRecord(
            instruction.idempotencyKey(),
            instruction.batchId(),
            InstructionStatus.ROUTED,
            null,
            "x".repeat(600),
            T0);

    assertThat(row.getDebtor()).isNull();
    assertThat(row.getCreditor()).isNull();
    assertThat(row.getStatusDetail()).hasSize(InstructionRecord.MAX_DETAIL_LENGTH);
    assertThat(row.getBatchId()).isEqualTo(instruction.batchId());
  }

  @Test
  void historyEntriesKeepTheBatchNamedByTheEvent() {
    UUID otherBatch = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    InstructionStatusHistory entry =
        new InstructionStatusHistory(
            eventId,
            instruction.idempotencyKey(),
            otherBatch,
            InstructionStatus.DUPLICATE,
            Channel.EFT,
            "already ROUTED",
            T0);

    assertThat(entry.getEventId()).isEqualTo(eventId.toString());
    assertThat(entry.getBatchId()).isEqualTo(otherBatch);
    assertThat(entry.getStatus()).isEqualTo(InstructionStatus.DUPLICATE);
    assertThat(entry.getDetail()).isEqualTo("already ROUTED");
    assertThat(entry.getIdempotencyKey()).isEqualTo(instruction.idempotencyKey());
    assertThat(entry.getChannel()).isEqualTo(Channel.EFT);
    assertThat(entry.getOccurredAt()).isEqualTo(T0);
  }

  private InstructionRecord routed(Instant at) {
    InstructionRecord row =
        new InstructionRecord(
            instruction.idempotencyKey(),
            instruction.batchId(),
            InstructionStatus.ROUTED,
            Channel.EFT,
            null,
            at);
    row.applyDetails(instruction);
    return row;
  }
}

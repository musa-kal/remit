package ca.musakaleem.remit.api.deadletter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.musakaleem.remit.api.support.TestEvents;
import ca.musakaleem.remit.common.event.InstructionStatus;
import ca.musakaleem.remit.common.event.PaymentInstruction;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class DeadLetterTest {

  private static final int MAX = 3;
  private static final Instant T0 = Instant.parse("2026-10-15T13:30:00Z");

  private final UUID batchId = UUID.randomUUID();

  @Test
  void aReadableRecordStartsPendingWithTheInstructionsReplayCount() {
    DeadLetter deadLetter = DeadLetter.from(record(0, 7), MAX);

    assertThat(deadLetter.getStatus()).isEqualTo(DeadLetterStatus.PENDING);
    assertThat(deadLetter.getReplayCount()).isZero();
    assertThat(deadLetter.getIdempotencyKey()).hasSize(64);
    assertThat(deadLetter.getBatchId()).isEqualTo(batchId);
    assertThat(deadLetter.getOriginalOffset()).isEqualTo(7);
    assertThat(deadLetter.getOriginalPartition()).isEqualTo(1);
    assertThat(deadLetter.getOriginalTopic()).isEqualTo("payments.instructions");
    assertThat(deadLetter.getExceptionClass()).endsWith("RoutingException");
    assertThat(deadLetter.getFirstSeenAt()).isEqualTo(T0);
    assertThat(deadLetter.getLastReplayedAt()).isNull();
  }

  @Test
  void aRecordAlreadyReplayedThreeTimesStartsExhausted() {
    assertThat(DeadLetter.from(record(3, 7), MAX).getStatus())
        .isEqualTo(DeadLetterStatus.EXHAUSTED);
  }

  @Test
  void aPoisonPillHasNoKeyAndTakesTheBatchFromTheCorrelationHeader() {
    DeadLetter pill = DeadLetter.from(poison(batchId.toString()), MAX);
    DeadLetter noBatch = DeadLetter.from(poison("not-a-uuid"), MAX);

    assertThat(pill.getIdempotencyKey()).isNull();
    assertThat(pill.getBatchId()).isEqualTo(batchId);
    assertThat(noBatch.getBatchId()).isNull();
    assertThat(pill.getPayload()).contains("unreadable");
  }

  @Test
  void aReplayIncrementsTheCountAndWaitsForTheOutcome() {
    DeadLetter deadLetter = DeadLetter.from(record(0, 7), MAX);

    deadLetter.requestReplay(MAX, T0.plusSeconds(60));

    assertThat(deadLetter.getStatus()).isEqualTo(DeadLetterStatus.REPLAY_REQUESTED);
    assertThat(deadLetter.getReplayCount()).isEqualTo(1);
    assertThat(deadLetter.getLastReplayedAt()).isEqualTo(T0.plusSeconds(60));
    assertThat(deadLetter.getUpdatedAt()).isEqualTo(T0.plusSeconds(60));
  }

  @Test
  void aSecondReplayWhileOneIsPendingIsRefused() {
    DeadLetter deadLetter = DeadLetter.from(record(0, 7), MAX);
    deadLetter.requestReplay(MAX, T0);

    assertThatThrownBy(() -> deadLetter.requestReplay(MAX, T0.plusSeconds(1)))
        .isInstanceOf(ReplayRejectedException.class)
        .hasMessageContaining("already pending");
    assertThat(deadLetter.getReplayCount()).isEqualTo(1);
  }

  @Test
  void theFourthReplayIsRefusedAndMarksTheDeadLetterExhausted() {
    DeadLetter deadLetter = DeadLetter.from(record(0, 7), MAX);
    for (int replay = 1; replay <= 3; replay++) {
      Instant at = T0.plusSeconds(replay * 60L);
      deadLetter.requestReplay(MAX, at);
      deadLetter.onInstructionStatus(InstructionStatus.DEAD_LETTERED, at.plusSeconds(5), MAX);
    }

    assertThat(deadLetter.getReplayCount()).isEqualTo(3);
    assertThat(deadLetter.getStatus()).isEqualTo(DeadLetterStatus.EXHAUSTED);
    assertThatThrownBy(() -> deadLetter.requestReplay(MAX, T0.plusSeconds(600)))
        .isInstanceOf(ReplayRejectedException.class)
        .hasMessageContaining("limit of 3 replays");
    assertThat(deadLetter.getStatus()).isEqualTo(DeadLetterStatus.EXHAUSTED);
    assertThat(deadLetter.getReplayCount()).isEqualTo(3);
  }

  @Test
  void aFailedReplayBelowTheCapGoesBackToPending() {
    DeadLetter deadLetter = DeadLetter.from(record(0, 7), MAX);
    deadLetter.requestReplay(MAX, T0);

    assertThat(deadLetter.onInstructionStatus(InstructionStatus.DEAD_LETTERED, T0, MAX)).isTrue();

    assertThat(deadLetter.getStatus()).isEqualTo(DeadLetterStatus.PENDING);
  }

  @ParameterizedTest
  @EnumSource(
      value = InstructionStatus.class,
      names = {"REPLAYED", "ROUTED", "REQUIRES_REVIEW"})
  void aSuccessfulReplayMarksTheDeadLetterReplayedAndRefusesAnotherReplay(
      InstructionStatus outcome) {
    DeadLetter deadLetter = DeadLetter.from(record(0, 7), MAX);
    deadLetter.requestReplay(MAX, T0);

    assertThat(deadLetter.onInstructionStatus(outcome, T0.plusMillis(20), MAX)).isTrue();

    assertThat(deadLetter.getStatus()).isEqualTo(DeadLetterStatus.REPLAYED);
    assertThatThrownBy(() -> deadLetter.requestReplay(MAX, T0.plusSeconds(1)))
        .isInstanceOf(ReplayRejectedException.class)
        .hasMessageContaining("already replayed");
  }

  @Test
  void statusEventsFromBeforeTheReplayOrWithoutAPendingReplayAreIgnored() {
    DeadLetter deadLetter = DeadLetter.from(record(0, 7), MAX);

    assertThat(deadLetter.onInstructionStatus(InstructionStatus.ROUTED, T0, MAX)).isFalse();
    deadLetter.requestReplay(MAX, T0.plusSeconds(60));
    assertThat(deadLetter.onInstructionStatus(InstructionStatus.DEAD_LETTERED, T0, MAX)).isFalse();
    assertThat(deadLetter.onInstructionStatus(InstructionStatus.DUPLICATE, T0.plusSeconds(61), MAX))
        .isFalse();
    assertThat(deadLetter.getStatus()).isEqualTo(DeadLetterStatus.REPLAY_REQUESTED);
  }

  @Test
  void anUnreadableRecordCannotBeReplayed() {
    DeadLetter pill = DeadLetter.from(poison(null), MAX);

    assertThatThrownBy(() -> pill.requestReplay(MAX, T0))
        .isInstanceOf(ReplayRejectedException.class)
        .hasMessageContaining("unreadable");
  }

  @Test
  void theDltRecordOfAFailedReplayUpdatesTheOpenDeadLetter() {
    DeadLetter deadLetter = DeadLetter.from(record(0, 7), MAX);
    deadLetter.requestReplay(MAX, T0.plusSeconds(60));
    DeadLetterRecord again = record(deadLetter, 1, 99, T0.plusSeconds(65));

    assertThat(deadLetter.replayFailed(again, MAX)).isTrue();

    assertThat(deadLetter.getStatus()).isEqualTo(DeadLetterStatus.PENDING);
    assertThat(deadLetter.getReplayCount()).isEqualTo(1);
    assertThat(deadLetter.getOriginalOffset()).isEqualTo(7);
    assertThat(deadLetter.getUpdatedAt()).isEqualTo(T0.plusSeconds(65));
  }

  @Test
  void aStaleDltRecordWithALowerReplayCountChangesNothing() {
    DeadLetter deadLetter = DeadLetter.from(record(0, 7), MAX);
    deadLetter.requestReplay(MAX, T0.plusSeconds(60));
    deadLetter.onInstructionStatus(InstructionStatus.DEAD_LETTERED, T0.plusSeconds(61), MAX);
    deadLetter.requestReplay(MAX, T0.plusSeconds(120));

    assertThat(deadLetter.replayFailed(record(deadLetter, 1, 99, T0.plusSeconds(65)), MAX))
        .isFalse();
    assertThat(deadLetter.replayFailed(poison(null), MAX)).isFalse();
    assertThat(deadLetter.getStatus()).isEqualTo(DeadLetterStatus.REPLAY_REQUESTED);
  }

  private DeadLetterRecord record(int replayCount, long offset) {
    PaymentInstruction instruction = TestEvents.instruction(batchId, replayCount);
    return new DeadLetterRecord(
        "payments.instructions",
        1,
        offset,
        "ca.musakaleem.remit.routing.error.RoutingException",
        "Forced failure",
        instruction,
        "{}",
        T0,
        batchId.toString());
  }

  private DeadLetterRecord record(
      DeadLetter source, int replayCount, long offset, Instant deadLetteredAt) {
    PaymentInstruction base = TestEvents.instruction(batchId, replayCount);
    PaymentInstruction same =
        new PaymentInstruction(
            source.getIdempotencyKey(),
            batchId,
            base.clientId(),
            base.instructionRef(),
            base.debtor(),
            base.creditor(),
            base.amount(),
            base.currency(),
            base.channel(),
            base.valueDate(),
            base.remittanceInfo(),
            replayCount);
    return new DeadLetterRecord(
        "payments.instructions",
        2,
        offset,
        "ca.musakaleem.remit.routing.error.RoutingException",
        "Forced failure again",
        same,
        "{\"replayCount\":" + replayCount + "}",
        deadLetteredAt,
        batchId.toString());
  }

  private static DeadLetterRecord poison(String correlationId) {
    return new DeadLetterRecord(
        "payments.instructions",
        0,
        3,
        "org.springframework.kafka.support.serializer.DeserializationException",
        "failed to deserialize",
        null,
        "{\"unreadable\":\"{not json\",\"sizeBytes\":9}",
        T0,
        correlationId);
  }
}

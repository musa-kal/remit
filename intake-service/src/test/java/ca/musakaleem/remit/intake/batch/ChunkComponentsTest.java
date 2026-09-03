package ca.musakaleem.remit.intake.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ca.musakaleem.remit.common.event.Channel;
import ca.musakaleem.remit.common.event.InstructionRejected;
import ca.musakaleem.remit.common.event.Party;
import ca.musakaleem.remit.common.event.PaymentInstruction;
import ca.musakaleem.remit.intake.publish.EventPublishException;
import ca.musakaleem.remit.intake.publish.IntakeEventPublisher;
import ca.musakaleem.remit.intake.validation.InstructionValidationException;
import ca.musakaleem.remit.intake.validation.RawInstruction;
import ca.musakaleem.remit.intake.validation.ReasonCode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.step.skip.NonSkippableProcessException;
import org.springframework.batch.core.step.skip.SkipLimitExceededException;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.test.MetaDataInstanceFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class ChunkComponentsTest {

  private static final UUID BATCH_ID = UUID.fromString("7f1c9a5e-2d3b-4c8e-9f10-1a2b3c4d5e6f");

  private final IntakeEventPublisher publisher = mock(IntakeEventPublisher.class);

  @Test
  void writerPublishesEveryInstructionThenAwaitsThemAll() {
    when(publisher.publishInstruction(any())).thenReturn(CompletableFuture.completedFuture(null));
    InstructionKafkaWriter writer = new InstructionKafkaWriter(publisher);

    writer.write(new Chunk<>(instruction("A-1"), instruction("A-2"), instruction("A-3")));

    verify(publisher, times(3)).publishInstruction(any());
    ArgumentCaptor<List<CompletableFuture<?>>> sends = ArgumentCaptor.forClass(List.class);
    verify(publisher).awaitAll(sends.capture());
    assertThat(sends.getValue()).hasSize(3);
  }

  @Test
  void writerFailsTheChunkWhenAPublishFails() {
    when(publisher.publishInstruction(any())).thenReturn(CompletableFuture.completedFuture(null));
    doThrow(new EventPublishException("down", null)).when(publisher).awaitAll(anyList());
    InstructionKafkaWriter writer = new InstructionKafkaWriter(publisher);

    assertThatThrownBy(() -> writer.write(new Chunk<>(instruction("A-1"))))
        .isInstanceOf(EventPublishException.class);
  }

  @Test
  void skipPolicySkipsValidationFailuresUpToTheLimit() {
    InstructionSkipPolicy policy = new InstructionSkipPolicy(2);
    InstructionValidationException invalid =
        new InstructionValidationException(2, List.of(ReasonCode.INVALID_AMOUNT));

    assertThat(policy.shouldSkip(invalid, 0)).isTrue();
    assertThat(policy.shouldSkip(invalid, 1)).isTrue();
    assertThatThrownBy(() -> policy.shouldSkip(invalid, 2))
        .isInstanceOf(SkipLimitExceededException.class);
  }

  @Test
  void skipPolicyNeverSkipsTechnicalFailures() {
    InstructionSkipPolicy policy = new InstructionSkipPolicy(1000);

    assertThat(policy.shouldSkip(new EventPublishException("down", null), 0)).isFalse();
    assertThat(policy.shouldSkip(new IllegalStateException(), 0)).isFalse();
  }

  @Test
  void validationExceptionNeedsAReason() {
    assertThatThrownBy(() -> new InstructionValidationException(2, List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void skipListenerPublishesAMaskedRejection(CapturedOutput output) {
    RejectedInstructionListener listener =
        new RejectedInstructionListener(publisher, BATCH_ID, "ACME01");
    String raw = "A-9,Jane,001,00011,1234567,John,002,00022,98765432109,0,CAD,EFT,2026-10-15,";
    RawInstruction row = RawInstruction.malformed(7, raw);

    listener.onSkipInProcess(
        row,
        new InstructionValidationException(
            7, List.of(ReasonCode.INVALID_AMOUNT, ReasonCode.INVALID_ACCOUNT)));

    ArgumentCaptor<InstructionRejected> captor = ArgumentCaptor.forClass(InstructionRejected.class);
    verify(publisher).publishRejection(captor.capture());
    InstructionRejected rejected = captor.getValue();
    assertThat(rejected.batchId()).isEqualTo(BATCH_ID);
    assertThat(rejected.clientId()).isEqualTo("ACME01");
    assertThat(rejected.lineNumber()).isEqualTo(7);
    assertThat(rejected.reasonCodes()).containsExactly("INVALID_AMOUNT", "INVALID_ACCOUNT");
    assertThat(rejected.rawLine())
        .contains("***4567")
        .contains("*******2109")
        .doesNotContain("1234567")
        .doesNotContain("98765432109");
    assertThat(output.getAll()).doesNotContain("1234567").doesNotContain("98765432109");
  }

  @Test
  void skipListenerFallsBackToMalformedForUnexpectedSkips() {
    RejectedInstructionListener listener =
        new RejectedInstructionListener(publisher, BATCH_ID, "ACME01");

    listener.onSkipInProcess(RawInstruction.malformed(3, "x"), new IllegalStateException());

    ArgumentCaptor<InstructionRejected> captor = ArgumentCaptor.forClass(InstructionRejected.class);
    verify(publisher).publishRejection(captor.capture());
    assertThat(captor.getValue().reasonCodes()).containsExactly("MALFORMED_ROW");
    assertThat(captor.getValue().instructionRef()).isNull();
  }

  @Test
  void stepListenerMapsSkipLimitFailuresToTheirOwnExitCode() {
    InstructionStepListener listener = new InstructionStepListener();
    StepExecution failed = MetaDataInstanceFactory.createStepExecution();
    failed.setStatus(BatchStatus.FAILED);
    failed.addFailureException(new SkipLimitExceededException(3, new IllegalStateException("row")));

    assertThat(listener.afterStep(failed).getExitCode()).isEqualTo(IngestJob.SKIP_LIMIT_EXCEEDED);
  }

  @Test
  void stepListenerRecognisesAWrappedValidationFailure() {
    InstructionStepListener listener = new InstructionStepListener();
    StepExecution failed = MetaDataInstanceFactory.createStepExecution();
    failed.setStatus(BatchStatus.FAILED);
    failed.addFailureException(
        new NonSkippableProcessException(
            "no", new InstructionValidationException(2, List.of(ReasonCode.INVALID_NAME))));

    ExitStatus exit = listener.afterStep(failed);

    assertThat(exit.getExitCode()).isEqualTo(IngestJob.SKIP_LIMIT_EXCEEDED);
  }

  @Test
  void stepListenerLeavesOtherOutcomesAlone() {
    InstructionStepListener listener = new InstructionStepListener();
    StepExecution technical = MetaDataInstanceFactory.createStepExecution();
    technical.setStatus(BatchStatus.FAILED);
    technical.addFailureException(new EventPublishException("down", null));
    StepExecution completed = MetaDataInstanceFactory.createStepExecution();
    completed.setStatus(BatchStatus.COMPLETED);

    assertThat(listener.afterStep(technical)).isNull();
    assertThat(listener.afterStep(completed)).isNull();
  }

  private static PaymentInstruction instruction(String ref) {
    return new PaymentInstruction(
        "key-" + ref,
        BATCH_ID,
        "ACME01",
        ref,
        new Party("Jane Doe", "001", "00011", "1234567"),
        new Party("John Roe", "002", "00022", "7654321"),
        new BigDecimal("10.00"),
        "CAD",
        Channel.EFT,
        LocalDate.of(2026, 10, 15),
        null,
        0);
  }
}

package ca.musakaleem.remit.routing.service;

import static ca.musakaleem.remit.routing.support.TestInstructions.DEBTOR_ACCOUNT;
import static ca.musakaleem.remit.routing.support.TestInstructions.instruction;
import static ca.musakaleem.remit.routing.support.TestInstructions.withRemittance;
import static ca.musakaleem.remit.routing.support.TestInstructions.withReplayCount;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ca.musakaleem.remit.common.event.Channel;
import ca.musakaleem.remit.common.event.InstructionStatus;
import ca.musakaleem.remit.common.event.PaymentInstruction;
import ca.musakaleem.remit.routing.config.RoutingProperties;
import ca.musakaleem.remit.routing.config.RoutingProperties.ChannelRule;
import ca.musakaleem.remit.routing.error.NonRetryableRoutingException;
import ca.musakaleem.remit.routing.error.RoutingException;
import ca.musakaleem.remit.routing.ledger.ProcessedInstruction;
import ca.musakaleem.remit.routing.ledger.ProcessedInstructionRepository;
import ca.musakaleem.remit.routing.ledger.ProcessingStatus;
import ca.musakaleem.remit.routing.publish.RoutingEventPublisher;
import ca.musakaleem.remit.routing.rules.ChaosHook;
import ca.musakaleem.remit.routing.rules.RoutingDecision;
import ca.musakaleem.remit.routing.rules.RoutingRules;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

@ExtendWith(OutputCaptureExtension.class)
class RoutingServiceTest {

  private static final Instant EARLIER = Instant.parse("2026-10-15T13:00:00Z");
  private static final Instant NOW = Instant.parse("2026-10-15T13:30:00.123Z");

  private final ProcessedInstructionRepository ledger = mock(ProcessedInstructionRepository.class);
  private final PlatformTransactionManager transactionManager =
      mock(PlatformTransactionManager.class);
  private final RoutingEventPublisher publisher = mock(RoutingEventPublisher.class);

  private RoutingService service;

  @BeforeEach
  void setUp() {
    when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    service = service(false);
  }

  @Test
  void aNewInstructionIsClaimedRoutedRecordedAndAnnounced() {
    PaymentInstruction instruction = instruction(Channel.EFT, "1250.00", "CAD");
    AtomicReference<ProcessedInstruction> row = insertSucceeds(instruction);

    service.route(instruction);

    assertThat(row.get().getStatus()).isEqualTo(ProcessingStatus.ROUTED);
    assertThat(row.get().getAttempts()).isEqualTo(1);
    assertThat(row.get().getUpdatedAt()).isEqualTo(NOW);
    InOrder order = inOrder(ledger, transactionManager, publisher);
    order.verify(ledger).saveAndFlush(any());
    order.verify(transactionManager).commit(any());
    order
        .verify(publisher)
        .publishRouted(
            instruction,
            new RoutingDecision(Channel.EFT, "payments.routed.eft", ProcessingStatus.ROUTED));
    order.verify(transactionManager).commit(any());
    order.verify(publisher).publishStatus(instruction, InstructionStatus.ROUTED, null);
    order.verifyNoMoreInteractions();
  }

  @Test
  void aWireAtTheThresholdIsRecordedAndAnnouncedAsRequiresReview() {
    PaymentInstruction instruction = instruction(Channel.WIRE, "1000000.00", "CAD");
    AtomicReference<ProcessedInstruction> row = insertSucceeds(instruction);

    service.route(instruction);

    assertThat(row.get().getStatus()).isEqualTo(ProcessingStatus.REQUIRES_REVIEW);
    verify(publisher)
        .publishRouted(
            instruction,
            new RoutingDecision(
                Channel.WIRE, "payments.routed.wire", ProcessingStatus.REQUIRES_REVIEW));
    verify(publisher).publishStatus(instruction, InstructionStatus.REQUIRES_REVIEW, null);
  }

  @Test
  void aDuplicateOfARoutedInstructionIsAnnouncedAndNotRoutedAgain() {
    PaymentInstruction instruction = instruction(Channel.EFT, "1250.00", "CAD");
    ProcessedInstruction existing = existingRow(instruction, ProcessingStatus.ROUTED);

    service.route(instruction);

    verify(publisher).publishStatus(instruction, InstructionStatus.DUPLICATE, "already ROUTED");
    verify(publisher, never()).publishRouted(any(), any());
    verify(transactionManager).rollback(any());
    assertThat(existing.getAttempts()).as("a finished row is never touched again").isEqualTo(1);
    assertThat(existing.getStatus()).isEqualTo(ProcessingStatus.ROUTED);
  }

  @Test
  void aDuplicateOfAnInstructionUnderReviewIsAnnouncedAndNotRoutedAgain() {
    PaymentInstruction instruction = instruction(Channel.WIRE, "1500000.00", "USD");
    existingRow(instruction, ProcessingStatus.REQUIRES_REVIEW);

    service.route(instruction);

    verify(publisher)
        .publishStatus(instruction, InstructionStatus.DUPLICATE, "already REQUIRES_REVIEW");
    verify(publisher, never()).publishRouted(any(), any());
  }

  @Test
  void aRowLeftInRoutingIsResumedCountedAndFinished() {
    PaymentInstruction instruction = instruction(Channel.INTERAC, "500.00", "CAD");
    ProcessedInstruction existing = existingRow(instruction, ProcessingStatus.ROUTING);

    service.route(instruction);

    assertThat(existing.getAttempts()).isEqualTo(2);
    assertThat(existing.getStatus()).isEqualTo(ProcessingStatus.ROUTED);
    assertThat(existing.getCreatedAt()).isEqualTo(EARLIER);
    verify(publisher).publishRouted(eq(instruction), any());
    verify(publisher).publishStatus(instruction, InstructionStatus.ROUTED, null);
    verify(publisher, never()).publishStatus(any(), eq(InstructionStatus.DUPLICATE), any());
  }

  @Test
  void anInsertFailureWithoutAnExistingRowIsRethrown() {
    PaymentInstruction instruction = instruction(Channel.EFT, "1250.00", "CAD");
    DataIntegrityViolationException failure = new DataIntegrityViolationException("too long");
    when(ledger.saveAndFlush(any())).thenThrow(failure);
    when(ledger.findById(instruction.idempotencyKey())).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.route(instruction)).isSameAs(failure);

    verifyNoInteractions(publisher);
  }

  @Test
  void aReplayAnnouncesReplayedBeforeTheFinalStatus() {
    PaymentInstruction replay = withReplayCount(instruction(Channel.EFT, "1250.00", "CAD"), 1);
    existingRow(replay, ProcessingStatus.ROUTING);

    service.route(replay);

    InOrder order = inOrder(publisher, transactionManager);
    order.verify(publisher).publishRouted(eq(replay), any());
    order.verify(transactionManager).commit(any());
    order.verify(publisher).publishStatus(replay, InstructionStatus.REPLAYED, "replay 1");
    order.verify(publisher).publishStatus(replay, InstructionStatus.ROUTED, null);
  }

  @Test
  void aFirstDeliveryAnnouncesNoReplay() {
    PaymentInstruction instruction = instruction(Channel.EFT, "1250.00", "CAD");
    insertSucceeds(instruction);

    service.route(instruction);

    verify(publisher, never()).publishStatus(any(), eq(InstructionStatus.REPLAYED), any());
  }

  @Test
  void theChaosHookFailsAfterTheAttemptIsCounted() {
    RoutingService chaotic = service(true);
    PaymentInstruction forceFail =
        withRemittance(instruction(Channel.EFT, "10.00", "CAD"), "FORCE_FAIL");
    ProcessedInstruction existing = existingRow(forceFail, ProcessingStatus.ROUTING);

    assertThatThrownBy(() -> chaotic.route(forceFail)).isInstanceOf(RoutingException.class);

    assertThat(existing.getAttempts()).isEqualTo(2);
    assertThat(existing.getStatus()).isEqualTo(ProcessingStatus.ROUTING);
    verifyNoInteractions(publisher);
  }

  @Test
  void withTheChaosHookOffForceFailIsRoutedNormally() {
    PaymentInstruction forceFail =
        withRemittance(instruction(Channel.EFT, "10.00", "CAD"), "FORCE_FAIL");
    insertSucceeds(forceFail);

    service.route(forceFail);

    verify(publisher).publishStatus(forceFail, InstructionStatus.ROUTED, null);
  }

  @Test
  void aChannelRuleViolationIsNotRetryableAndLeavesTheClaimedRow() {
    PaymentInstruction usdInterac = instruction(Channel.INTERAC, "100.00", "USD");
    AtomicReference<ProcessedInstruction> row = insertSucceeds(usdInterac);

    assertThatThrownBy(() -> service.route(usdInterac))
        .isInstanceOf(NonRetryableRoutingException.class)
        .hasMessage("INTERAC does not accept currency USD");

    assertThat(row.get().getStatus()).isEqualTo(ProcessingStatus.ROUTING);
    assertThat(row.get().getAttempts()).isEqualTo(1);
    verifyNoInteractions(publisher);
  }

  @Test
  void aFailedRoutedPublishLeavesTheRowRoutingAndAnnouncesNothing() {
    PaymentInstruction instruction = instruction(Channel.EFT, "1250.00", "CAD");
    AtomicReference<ProcessedInstruction> row = insertSucceeds(instruction);
    doThrow(new RoutingException("Kafka rejected routed instruction"))
        .when(publisher)
        .publishRouted(any(), any());

    assertThatThrownBy(() -> service.route(instruction)).isInstanceOf(RoutingException.class);

    assertThat(row.get().getStatus()).isEqualTo(ProcessingStatus.ROUTING);
    verify(transactionManager, times(1)).commit(any());
    verify(publisher, never()).publishStatus(any(), any(), any());
  }

  @Test
  void anIncompleteInstructionIsRejectedBeforeTouchingTheLedger() {
    assertThatThrownBy(() -> service.route(null)).isInstanceOf(NonRetryableRoutingException.class);

    verifyNoInteractions(ledger, transactionManager, publisher);
  }

  @Test
  void theClaimedRowDisappearingBeforeTheUpdateFailsTheAttempt() {
    PaymentInstruction instruction = instruction(Channel.EFT, "1250.00", "CAD");
    when(ledger.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
    when(ledger.findById(anyString())).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.route(instruction))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Claimed row disappeared");
    verify(publisher, never()).publishStatus(any(), any(), isNull());
  }

  @Test
  void logsOnlyTheMaskedDebtorAccount(CapturedOutput output) {
    PaymentInstruction instruction = instruction(Channel.EFT, "1250.00", "CAD");
    insertSucceeds(instruction);

    service.route(instruction);

    assertThat(output).contains("debtor account ******1234").doesNotContain(DEBTOR_ACCOUNT);
  }

  /** Lets the insert succeed and returns a holder for the row the service inserts. */
  private AtomicReference<ProcessedInstruction> insertSucceeds(PaymentInstruction instruction) {
    AtomicReference<ProcessedInstruction> inserted = new AtomicReference<>();
    when(ledger.saveAndFlush(any()))
        .thenAnswer(
            call -> {
              inserted.set(call.getArgument(0));
              return call.getArgument(0);
            });
    when(ledger.findById(instruction.idempotencyKey()))
        .thenAnswer(call -> Optional.ofNullable(inserted.get()));
    return inserted;
  }

  private ProcessedInstruction existingRow(
      PaymentInstruction instruction, ProcessingStatus status) {
    ProcessedInstruction existing =
        new ProcessedInstruction(
            instruction.idempotencyKey(),
            instruction.batchId(),
            instruction.channel(),
            instruction.amount(),
            EARLIER);
    if (status.isFinal()) {
      existing.finish(status, EARLIER);
    }
    when(ledger.saveAndFlush(any()))
        .thenThrow(new DataIntegrityViolationException("Duplicate entry for key PRIMARY"));
    when(ledger.findById(instruction.idempotencyKey())).thenReturn(Optional.of(existing));
    return existing;
  }

  private RoutingService service(boolean chaosEnabled) {
    RoutingProperties properties =
        new RoutingProperties(
            Map.of(
                Channel.INTERAC, new ChannelRule(new BigDecimal("10000.00"), Set.of("CAD")),
                Channel.EFT, new ChannelRule(new BigDecimal("25000000.00"), null)),
            new BigDecimal("1000000.00"),
            chaosEnabled,
            Duration.ofSeconds(1));
    return new RoutingService(
        ledger,
        transactionManager,
        new RoutingRules(properties),
        new ChaosHook(properties),
        publisher,
        Clock.fixed(NOW, ZoneOffset.UTC));
  }
}

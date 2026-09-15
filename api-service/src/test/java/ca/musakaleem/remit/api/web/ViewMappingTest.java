package ca.musakaleem.remit.api.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.musakaleem.remit.api.deadletter.DeadLetter;
import ca.musakaleem.remit.api.deadletter.DeadLetterRecord;
import ca.musakaleem.remit.api.deadletter.DeadLetterStatus;
import ca.musakaleem.remit.api.deadletter.DeadLetterView;
import ca.musakaleem.remit.api.instruction.InstructionDetailView;
import ca.musakaleem.remit.api.instruction.InstructionRecord;
import ca.musakaleem.remit.api.instruction.InstructionStatusHistory;
import ca.musakaleem.remit.api.instruction.InstructionView;
import ca.musakaleem.remit.api.instruction.PartyView;
import ca.musakaleem.remit.api.rejection.InstructionRejection;
import ca.musakaleem.remit.api.rejection.RejectionView;
import ca.musakaleem.remit.api.security.Callers;
import ca.musakaleem.remit.api.support.TestEvents;
import ca.musakaleem.remit.common.event.Channel;
import ca.musakaleem.remit.common.event.InstructionRejected;
import ca.musakaleem.remit.common.event.InstructionStatus;
import ca.musakaleem.remit.common.event.PaymentInstruction;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;

class ViewMappingTest {

  private static final Instant T0 = Instant.parse("2026-10-15T13:30:00Z");

  private final PaymentInstruction instruction = TestEvents.instruction(UUID.randomUUID(), 0);

  @Test
  void listItemsAlwaysMaskAccountNumbers() {
    InstructionView view = InstructionView.masked(row());

    assertThat(view.debtor().account()).isEqualTo("******1234");
    assertThat(view.creditor().account()).isEqualTo("******5678");
    assertThat(view.debtor().name()).isEqualTo("Test Debtor Ltd");
    assertThat(view.amount()).isEqualByComparingTo("1250.00");
  }

  @Test
  void theDetailIsUnmaskedOnlyWhenAskedTo() {
    List<InstructionStatusHistory> history =
        List.of(
            new InstructionStatusHistory(
                UUID.randomUUID(),
                instruction.idempotencyKey(),
                instruction.batchId(),
                InstructionStatus.ROUTED,
                Channel.EFT,
                null,
                T0));

    InstructionDetailView masked = InstructionDetailView.of(row(), history, false);
    InstructionDetailView unmasked = InstructionDetailView.of(row(), history, true);

    assertThat(masked.accountsMasked()).isTrue();
    assertThat(masked.debtor().account()).isEqualTo("******1234");
    assertThat(unmasked.accountsMasked()).isFalse();
    assertThat(unmasked.debtor().account()).isEqualTo(TestEvents.DEBTOR_ACCOUNT);
    assertThat(unmasked.creditor().account()).isEqualTo(TestEvents.CREDITOR_ACCOUNT);
    assertThat(unmasked.history())
        .singleElement()
        .satisfies(
            h -> {
              assertThat(h.status()).isEqualTo(InstructionStatus.ROUTED);
              assertThat(h.occurredAt()).isEqualTo(T0);
            });
  }

  @Test
  void anUnknownPartyStaysNull() {
    assertThat(PartyView.of(null, true)).isNull();
  }

  @Test
  void rejectedLinesAreMaskedAgainOnTheWayOut() {
    InstructionRejection rejection =
        new InstructionRejection(
            new InstructionRejected(
                UUID.randomUUID(),
                "TEST01",
                4,
                "ACME-0103",
                List.of("INVALID_ACCOUNT", "INVALID_AMOUNT"),
                "ACME-0103,Acme,001,00011,123456,Northwind,002,00022,2000001234,0.00"),
            T0);

    RejectionView view = RejectionView.of(rejection);

    assertThat(view.rawLine()).contains(",******1234,").doesNotContain("2000001234");
    assertThat(view.reasonCodes()).containsExactly("INVALID_ACCOUNT", "INVALID_AMOUNT");
    assertThat(view.lineNumber()).isEqualTo(4);
    assertThat(rejection.getClientId()).isEqualTo("TEST01");
  }

  @Test
  void aRejectionWithoutReasonsHasAnEmptyList() {
    InstructionRejection rejection =
        new InstructionRejection(
            new InstructionRejected(UUID.randomUUID(), "TEST01", 2, null, null, null), T0);

    assertThat(rejection.getReasonCodes()).isEmpty();
  }

  @Test
  void onlyAPendingReadableDeadLetterUnderTheCapIsReplayable() {
    DeadLetterRecord record =
        new DeadLetterRecord(
            "payments.instructions",
            0,
            1,
            "X",
            "boom",
            instruction,
            "{}",
            T0,
            instruction.batchId().toString());
    DeadLetter pending = DeadLetter.from(record, 3);
    DeadLetter requested = DeadLetter.from(record, 3);
    requested.requestReplay(3, T0);

    assertThat(DeadLetterView.of(pending, 3).replayable()).isTrue();
    assertThat(DeadLetterView.of(requested, 3).replayable()).isFalse();
    assertThat(DeadLetterView.of(requested, 3).status())
        .isEqualTo(DeadLetterStatus.REPLAY_REQUESTED);
  }

  @Test
  void pagesAreWrappedInTheEnvelope() {
    PageResponse<String> page =
        PageResponse.of(new PageImpl<>(List.of(1, 2), PageRequest.of(1, 2), 5), String::valueOf);

    assertThat(page.content()).containsExactly("1", "2");
    assertThat(page.page()).isEqualTo(1);
    assertThat(page.size()).isEqualTo(2);
    assertThat(page.totalElements()).isEqualTo(5);
    assertThat(page.totalPages()).isEqualTo(3);
  }

  @Test
  void statusCountsListEveryStatus() {
    Map<InstructionStatus, Long> counts =
        StatusCounts.of(
            InstructionStatus.class,
            List.<Object[]>of(new Object[] {InstructionStatus.ROUTED, 28L}));

    assertThat(counts)
        .containsEntry(InstructionStatus.ROUTED, 28L)
        .containsEntry(InstructionStatus.DUPLICATE, 0L)
        .hasSize(InstructionStatus.values().length);
  }

  @Test
  void onlyWhitelistedSortPropertiesAreAccepted() {
    PageRequest good = PageRequest.of(0, 10, Sort.by("receivedAt"));
    PageRequest bad = PageRequest.of(0, 10, Sort.by("passwordHash"));

    assertThat(Requests.sortableBy(good, Set.of("receivedAt"))).isSameAs(good);
    assertThatThrownBy(() -> Requests.sortableBy(bad, Set.of("receivedAt", "status")))
        .isInstanceOf(InvalidRequestException.class)
        .hasMessage("cannot sort by 'passwordHash'; allowed: receivedAt, status")
        .extracting(e -> ((InvalidRequestException) e).getField())
        .isEqualTo("sort");
  }

  @Test
  void aRangeMustStartBeforeItEnds() {
    Requests.range(null, T0);
    Requests.range(T0, null);
    Requests.range(T0, T0.plusSeconds(1));

    assertThatThrownBy(() -> Requests.range(T0, T0))
        .isInstanceOf(InvalidRequestException.class)
        .hasMessage("must be before 'to'");
  }

  @Test
  void onlyTheAdminRoleUnmasks() {
    assertThat(Callers.isAdmin(null)).isFalse();
    assertThat(
            Callers.isAdmin(
                UsernamePasswordAuthenticationToken.authenticated(
                    "operator", null, AuthorityUtils.createAuthorityList("ROLE_OPERATOR"))))
        .isFalse();
    assertThat(
            Callers.isAdmin(
                UsernamePasswordAuthenticationToken.authenticated(
                    "admin",
                    null,
                    AuthorityUtils.createAuthorityList("ROLE_OPERATOR", "ROLE_ADMIN"))))
        .isTrue();
  }

  private InstructionRecord row() {
    InstructionRecord row =
        new InstructionRecord(
            instruction.idempotencyKey(),
            instruction.batchId(),
            InstructionStatus.ROUTED,
            Channel.EFT,
            null,
            T0);
    row.applyDetails(instruction);
    return row;
  }
}

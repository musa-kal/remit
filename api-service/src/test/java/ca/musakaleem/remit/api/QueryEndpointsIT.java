package ca.musakaleem.remit.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.musakaleem.remit.api.support.AbstractApiIT;
import ca.musakaleem.remit.api.support.Fixtures;
import ca.musakaleem.remit.api.support.TestEvents;
import ca.musakaleem.remit.common.IdempotencyKeys;
import ca.musakaleem.remit.common.event.Channel;
import ca.musakaleem.remit.common.event.InstructionStatus;
import ca.musakaleem.remit.common.event.PaymentInstruction;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.http.MediaType;

/** Filters, paging, sorting, validation and masking of the read endpoints. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QueryEndpointsIT extends AbstractApiIT {

  private static final Instant T0 = Instant.parse("2026-10-15T10:00:00Z");

  private final String clientId =
      "Q" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT).replace("-", "0");
  private final UUID first = UUID.randomUUID();
  private final UUID second = UUID.randomUUID();
  private final UUID third = UUID.randomUUID();
  private PaymentInstruction eft;
  private PaymentInstruction wire;

  @BeforeAll
  void insertFixtures() {
    Fixtures fixtures = new Fixtures(jdbc);
    fixtures.batch(first, clientId, "COMPLETED", T0);
    fixtures.batch(second, clientId, "FAILED", T0.plusSeconds(3600));
    fixtures.batch(third, clientId, "COMPLETED_WITH_ERRORS", T0.plusSeconds(7200));
    eft = TestEvents.instruction(first, 0);
    PaymentInstruction base = TestEvents.instruction(first, 0);
    wire =
        new PaymentInstruction(
            IdempotencyKeys.forInstruction("TEST01", base.instructionRef() + "W"),
            first,
            base.clientId(),
            base.instructionRef() + "W",
            base.debtor(),
            base.creditor(),
            base.amount(),
            "USD",
            Channel.WIRE,
            base.valueDate(),
            base.remittanceInfo(),
            0);
    fixtures.instruction(eft, InstructionStatus.ROUTED);
    fixtures.instruction(wire, InstructionStatus.REQUIRES_REVIEW);
    fixtures.rejection(first, 7);
    fixtures.rejection(first, 3);
  }

  @Test
  void listsTheBatchesOfAClientNewestFirstInThePageEnvelope() throws Exception {
    mvc.perform(
            get("/api/v1/batches").param("clientId", clientId).header("Authorization", operator()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(3))
        .andExpect(jsonPath("$.totalPages").value(1))
        .andExpect(jsonPath("$.page").value(0))
        .andExpect(jsonPath("$.size").value(20))
        .andExpect(jsonPath("$.content[0].batchId").value(third.toString()))
        .andExpect(jsonPath("$.content[2].batchId").value(first.toString()))
        .andExpect(jsonPath("$.content[0].controlTotal").value(12500.00))
        .andExpect(jsonPath("$.content[0].receivedAt").value("2026-10-15T12:00:00Z"));
  }

  @Test
  void filtersByStatusAndReceivedRange() throws Exception {
    mvc.perform(
            get("/api/v1/batches")
                .param("clientId", clientId)
                .param("status", "FAILED")
                .header("Authorization", operator()))
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].batchId").value(second.toString()));
    mvc.perform(
            get("/api/v1/batches")
                .param("clientId", clientId)
                .param("from", "2026-10-15T10:30:00Z")
                .param("to", "2026-10-15T12:00:00Z")
                .header("Authorization", operator()))
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].batchId").value(second.toString()));
  }

  @Test
  void pagesAndSortsOnRequest() throws Exception {
    mvc.perform(
            get("/api/v1/batches")
                .param("clientId", clientId)
                .param("page", "1")
                .param("size", "2")
                .param("sort", "receivedAt,asc")
                .header("Authorization", operator()))
        .andExpect(jsonPath("$.page").value(1))
        .andExpect(jsonPath("$.size").value(2))
        .andExpect(jsonPath("$.totalPages").value(2))
        .andExpect(jsonPath("$.content.length()").value(1))
        .andExpect(jsonPath("$.content[0].batchId").value(third.toString()));
  }

  @Test
  void clampsPageSizesAbove100To100() throws Exception {
    mvc.perform(
            get("/api/v1/batches")
                .param("clientId", clientId)
                .param("size", "500")
                .header("Authorization", operator()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.size").value(100));
  }

  @Test
  void refusesInvalidParametersWithFieldErrors() throws Exception {
    expectFieldError(get("/api/v1/batches").param("status", "BOGUS"), "status");
    expectFieldError(get("/api/v1/batches").param("clientId", "lower"), "clientId");
    expectFieldError(get("/api/v1/batches").param("sort", "fileHash"), "sort");
    expectFieldError(get("/api/v1/batches").param("from", "yesterday"), "from");
    expectFieldError(
        get("/api/v1/batches")
            .param("from", "2026-10-16T00:00:00Z")
            .param("to", "2026-10-15T00:00:00Z"),
        "from");
    expectFieldError(get("/api/v1/batches/not-a-uuid"), "batchId");
    expectFieldError(get("/api/v1/instructions/not-a-key"), "idempotencyKey");
  }

  @Test
  void unknownBatchesAndInstructionsAre404Problems() throws Exception {
    mvc.perform(get("/api/v1/batches/" + UUID.randomUUID()).header("Authorization", operator()))
        .andExpect(status().isNotFound())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.title").value("Not Found"));
    mvc.perform(
            get("/api/v1/batches/" + UUID.randomUUID() + "/instructions")
                .header("Authorization", operator()))
        .andExpect(status().isNotFound());
    mvc.perform(get("/api/v1/instructions/" + "f".repeat(64)).header("Authorization", admin()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.detail").value("Instruction " + "f".repeat(64) + " not found"));
  }

  @Test
  void batchDetailCountsInstructionsByStatusAndRejections() throws Exception {
    mvc.perform(get("/api/v1/batches/" + first).header("Authorization", operator()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("COMPLETED"))
        .andExpect(jsonPath("$.totalRows").value(10))
        .andExpect(jsonPath("$.acceptedRows").value(8))
        .andExpect(jsonPath("$.rejectedRows").value(2))
        .andExpect(jsonPath("$.controlTotal").value(12500.00))
        .andExpect(jsonPath("$.instructionsByStatus.ROUTED").value(1))
        .andExpect(jsonPath("$.instructionsByStatus.REQUIRES_REVIEW").value(1))
        .andExpect(jsonPath("$.instructionsByStatus.DUPLICATE").value(0))
        .andExpect(jsonPath("$.rejectionCount").value(2))
        .andExpect(jsonPath("$.deadLetterCount").value(0));
  }

  @Test
  void batchInstructionsFilterByStatusAndChannelAndAreMaskedEvenForAdmin() throws Exception {
    mvc.perform(get("/api/v1/batches/" + first + "/instructions").header("Authorization", admin()))
        .andExpect(jsonPath("$.totalElements").value(2))
        .andExpect(
            jsonPath("$.content[*].debtor.account", Matchers.everyItem(Matchers.is("******1234"))))
        .andExpect(
            jsonPath(
                "$.content[*].creditor.account", Matchers.everyItem(Matchers.is("******5678"))));
    mvc.perform(
            get("/api/v1/batches/" + first + "/instructions")
                .param("channel", "WIRE")
                .header("Authorization", operator()))
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].idempotencyKey").value(wire.idempotencyKey()))
        .andExpect(jsonPath("$.content[0].status").value("REQUIRES_REVIEW"));
    mvc.perform(
            get("/api/v1/batches/" + first + "/instructions")
                .param("status", "ROUTED")
                .header("Authorization", operator()))
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].channel").value("EFT"));
  }

  @Test
  void rejectionsAreListedByLineNumber() throws Exception {
    mvc.perform(get("/api/v1/batches/" + first + "/rejections").header("Authorization", operator()))
        .andExpect(jsonPath("$.totalElements").value(2))
        .andExpect(jsonPath("$.content[0].lineNumber").value(3))
        .andExpect(jsonPath("$.content[1].lineNumber").value(7))
        .andExpect(jsonPath("$.content[0].reasonCodes[0]").value("INVALID_ACCOUNT"))
        .andExpect(jsonPath("$.content[0].rawLine").value("ACME-0103,Acme,001,00011,***1234"));
  }

  @Test
  void instructionDetailIsMaskedForAnOperator() throws Exception {
    mvc.perform(
            get("/api/v1/instructions/" + eft.idempotencyKey()).header("Authorization", operator()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accountsMasked").value(true))
        .andExpect(jsonPath("$.debtor.account").value("******1234"))
        .andExpect(jsonPath("$.creditor.account").value("******5678"))
        .andExpect(jsonPath("$.debtor.name").value("Test Debtor Ltd"))
        .andExpect(jsonPath("$.amount").value(1250.00))
        .andExpect(jsonPath("$.valueDate").value("2026-10-15"));
  }

  @Test
  void instructionDetailIsUnmaskedForAnAdmin() throws Exception {
    mvc.perform(
            get("/api/v1/instructions/" + eft.idempotencyKey()).header("Authorization", admin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accountsMasked").value(false))
        .andExpect(jsonPath("$.debtor.account").value(TestEvents.DEBTOR_ACCOUNT))
        .andExpect(jsonPath("$.creditor.account").value(TestEvents.CREDITOR_ACCOUNT))
        .andExpect(jsonPath("$.history").isArray());
  }

  @Test
  void statsCountBatchesInstructionsAndDeadLettersPerStatus() throws Exception {
    mvc.perform(
            get("/api/v1/stats/summary")
                .param("from", "2026-10-15T09:00:00Z")
                .param("to", "2026-10-15T13:00:00Z")
                .header("Authorization", operator()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.from").value("2026-10-15T09:00:00Z"))
        .andExpect(jsonPath("$.batches.COMPLETED").value(Matchers.greaterThanOrEqualTo(1)))
        .andExpect(jsonPath("$.batches.FAILED").value(Matchers.greaterThanOrEqualTo(1)))
        .andExpect(jsonPath("$.batches.RECEIVED").isNumber())
        .andExpect(jsonPath("$.instructions.ROUTED").isNumber())
        .andExpect(jsonPath("$.deadLetters.PENDING").isNumber())
        .andExpect(jsonPath("$.duplicateDeliveries").isNumber())
        .andExpect(jsonPath("$.rejectedRows").isNumber());
    expectFieldError(
        get("/api/v1/stats/summary")
            .param("from", "2026-10-15T13:00:00Z")
            .param("to", "2026-10-15T09:00:00Z"),
        "from");
  }

  private void expectFieldError(
      org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
      String field)
      throws Exception {
    mvc.perform(request.header("Authorization", operator()))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.errors[0].field").value(field));
  }
}

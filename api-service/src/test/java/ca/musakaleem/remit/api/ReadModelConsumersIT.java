package ca.musakaleem.remit.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.musakaleem.remit.api.audit.AuditEntityType;
import ca.musakaleem.remit.api.audit.AuditEvent;
import ca.musakaleem.remit.api.audit.AuditEventType;
import ca.musakaleem.remit.api.instruction.InstructionRecordRepository;
import ca.musakaleem.remit.api.support.AbstractApiIT;
import ca.musakaleem.remit.api.support.TestEvents;
import ca.musakaleem.remit.common.Topics;
import ca.musakaleem.remit.common.event.BatchEvent;
import ca.musakaleem.remit.common.event.BatchStatus;
import ca.musakaleem.remit.common.event.Channel;
import ca.musakaleem.remit.common.event.EventHeaders;
import ca.musakaleem.remit.common.event.EventType;
import ca.musakaleem.remit.common.event.InstructionRejected;
import ca.musakaleem.remit.common.event.InstructionStatus;
import ca.musakaleem.remit.common.event.InstructionStatusEvent;
import ca.musakaleem.remit.common.event.PaymentInstruction;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The read model consumers end to end over Kafka: batch events, status events, routed records and
 * rejected rows, each writing exactly one audit document.
 */
class ReadModelConsumersIT extends AbstractApiIT {

  @Autowired InstructionRecordRepository instructions;

  @Autowired TransactionTemplate transactions;

  private final Instant t0 = Instant.now().truncatedTo(ChronoUnit.MILLIS);

  @Test
  void receivedThenCompletedBuildsTheBatchAndTwoAuditDocuments() throws Exception {
    UUID batchId = UUID.randomUUID();

    publishBatch(TestEvents.batch(batchId, BatchStatus.RECEIVED, t0), null);
    publishBatch(TestEvents.batch(batchId, BatchStatus.COMPLETED, t0.plusSeconds(2)), null);

    Map<String, Object> row =
        eventually(
            () ->
                jdbc
                    .queryForList(
                        "SELECT status, accepted_rows, rejected_rows, control_total"
                            + " FROM payment_batch WHERE batch_id = ?",
                        batchId.toString())
                    .stream()
                    .filter(r -> "COMPLETED".equals(r.get("status")))
                    .findFirst()
                    .orElse(null));
    assertThat(row.get("accepted_rows")).isEqualTo(8);
    assertThat(row.get("rejected_rows")).isEqualTo(2);
    List<AuditEvent> audit = eventually(() -> atLeast(auditEvents(batchId.toString()), 2));
    assertThat(audit)
        .extracting(AuditEvent::eventType)
        .containsExactly(AuditEventType.BATCH_RECEIVED, AuditEventType.BATCH_COMPLETED);
    assertThat(audit)
        .allSatisfy(
            e -> {
              assertThat(e.actor()).isEqualTo("system:api-service");
              assertThat(e.entityType()).isEqualTo(AuditEntityType.BATCH);
              assertThat(e.correlationId()).isEqualTo(batchId.toString());
            });
    assertThat(audit.get(1).details())
        .containsEntry("status", "COMPLETED")
        .containsEntry("controlTotal", "12500.00")
        .containsEntry("readModelUpdated", true);
    mvc.perform(get("/api/v1/batches/" + batchId).header("Authorization", operator()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("COMPLETED"))
        .andExpect(jsonPath("$.receivedAt").value(t0.toString()));
  }

  @Test
  void anOlderBatchEventIsIgnoredButStillAudited() {
    UUID batchId = UUID.randomUUID();

    publishBatch(TestEvents.batch(batchId, BatchStatus.COMPLETED, t0.plusSeconds(5)), null);
    publishBatch(TestEvents.batch(batchId, BatchStatus.RECEIVED, t0), null);

    List<AuditEvent> audit = eventually(() -> atLeast(auditEvents(batchId.toString()), 2));
    assertThat(audit.get(0).details()).containsEntry("readModelUpdated", false);
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM payment_batch WHERE batch_id = ?",
                String.class,
                batchId.toString()))
        .isEqualTo("COMPLETED");
  }

  @Test
  void aWholeFileFailureKeepsItsReasonCode() {
    UUID batchId = UUID.randomUUID();

    publishBatch(
        TestEvents.batch(batchId, BatchStatus.FAILED, t0),
        kafkaHeader(EventHeaders.REASON_CODE, "DUPLICATE_FILE"));

    String reason =
        eventually(
            () ->
                jdbc.queryForObject(
                    "SELECT failure_reason FROM payment_batch WHERE batch_id = ?",
                    String.class,
                    batchId.toString()));
    assertThat(reason).isEqualTo("DUPLICATE_FILE");
  }

  @Test
  void aRedeliveredEventWithTheSameEventIdIsAuditedOnce() {
    UUID batchId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    BatchEvent event = TestEvents.batch(batchId, BatchStatus.RECEIVED, t0);

    for (int i = 0; i < 2; i++) {
      ProducerRecord<String, Object> record =
          new ProducerRecord<>(
              Topics.BATCH_EVENTS,
              null,
              batchId.toString(),
              event,
              EventHeaders.build(eventId, EventType.BATCH_RECEIVED, batchId.toString(), t0));
      kafkaTemplate.send(record).join();
    }
    publishBatch(TestEvents.batch(batchId, BatchStatus.COMPLETED, t0.plusSeconds(1)), null);

    // Same partition, so once BATCH_COMPLETED is audited both copies were consumed before it.
    List<AuditEvent> audit =
        eventually(
            () -> {
              List<AuditEvent> events = auditEvents(batchId.toString());
              return events.stream().anyMatch(e -> e.eventType() == AuditEventType.BATCH_COMPLETED)
                  ? events
                  : null;
            });
    assertThat(audit).hasSize(2);
    assertThat(audit.get(0).eventId()).isEqualTo(eventId.toString());
  }

  @Test
  void statusEventsBuildTheInstructionAndItsHistoryWithoutDowngradingOnDuplicate()
      throws Exception {
    PaymentInstruction instruction = TestEvents.instruction(UUID.randomUUID(), 0);
    UUID laterBatch = UUID.randomUUID();
    String key = instruction.idempotencyKey();

    publishStatus(instruction.batchId(), key, InstructionStatus.ROUTED, null, t0);
    publishStatus(
        laterBatch, key, InstructionStatus.DUPLICATE, "already ROUTED", t0.plusSeconds(60));

    List<AuditEvent> audit = eventually(() -> atLeast(auditEvents(key), 2));
    assertThat(audit)
        .extracting(AuditEvent::eventType)
        .containsOnly(AuditEventType.INSTRUCTION_STATUS_CHANGED);
    assertThat(audit.get(1).details())
        .containsEntry("status", "DUPLICATE")
        .containsEntry("readModelUpdated", false)
        .containsEntry("batchId", laterBatch.toString());
    mvc.perform(get("/api/v1/instructions/" + key).header("Authorization", operator()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ROUTED"))
        .andExpect(jsonPath("$.batchId").value(instruction.batchId().toString()))
        .andExpect(jsonPath("$.history.length()").value(2))
        .andExpect(jsonPath("$.history[0].status").value("ROUTED"))
        .andExpect(jsonPath("$.history[1].status").value("DUPLICATE"))
        .andExpect(jsonPath("$.history[1].detail").value("already ROUTED"))
        .andExpect(jsonPath("$.history[1].batchId").value(laterBatch.toString()));
  }

  @Test
  void anOlderStatusEventOnlyGoesIntoTheHistory() throws Exception {
    PaymentInstruction instruction = TestEvents.instruction(UUID.randomUUID(), 0);
    String key = instruction.idempotencyKey();

    publishStatus(instruction.batchId(), key, InstructionStatus.ROUTED, null, t0.plusSeconds(10));
    publishStatus(instruction.batchId(), key, InstructionStatus.DEAD_LETTERED, "late", t0);

    List<AuditEvent> audit = eventually(() -> atLeast(auditEvents(key), 2));
    assertThat(audit)
        .extracting(AuditEvent::eventType)
        .containsExactly(
            AuditEventType.INSTRUCTION_DEAD_LETTERED, AuditEventType.INSTRUCTION_STATUS_CHANGED);
    mvc.perform(get("/api/v1/instructions/" + key).header("Authorization", operator()))
        .andExpect(jsonPath("$.status").value("ROUTED"))
        .andExpect(jsonPath("$.history.length()").value(2));
  }

  @Test
  void aRoutedRecordFillsInThePaymentDetails() throws Exception {
    PaymentInstruction instruction = TestEvents.instruction(UUID.randomUUID(), 0);
    String key = instruction.idempotencyKey();

    publish(
        Topics.ROUTED_WIRE,
        key,
        instruction,
        EventType.INSTRUCTION_ROUTED,
        instruction.batchId().toString(),
        t0,
        kafkaHeader("routingStatus", "REQUIRES_REVIEW"));
    publishStatus(
        instruction.batchId(), key, InstructionStatus.REQUIRES_REVIEW, null, t0.plusMillis(5));

    List<AuditEvent> audit = eventually(() -> atLeast(auditEvents(key), 2));
    AuditEvent routed =
        audit.stream()
            .filter(e -> e.eventType() == AuditEventType.INSTRUCTION_ROUTED)
            .findFirst()
            .orElseThrow();
    assertThat(routed.details())
        .containsEntry("debtorAccount", "******1234")
        .containsEntry("routingStatus", "REQUIRES_REVIEW")
        .containsEntry("topic", Topics.ROUTED_WIRE);
    mvc.perform(get("/api/v1/instructions/" + key).header("Authorization", admin()))
        .andExpect(jsonPath("$.status").value("REQUIRES_REVIEW"))
        .andExpect(jsonPath("$.instructionRef").value(instruction.instructionRef()))
        .andExpect(jsonPath("$.debtor.account").value(TestEvents.DEBTOR_ACCOUNT))
        .andExpect(jsonPath("$.amount").value(1250.00))
        .andExpect(jsonPath("$.channel").value(Channel.EFT.name()));
  }

  @Test
  void aRejectedRowIsStoredOnceAndAudited() throws Exception {
    UUID batchId = UUID.randomUUID();
    InstructionRejected rejected =
        new InstructionRejected(
            batchId,
            "TEST01",
            4,
            "ACME-0103",
            List.of("INVALID_ACCOUNT"),
            "ACME-0103,Acme Widgets Ltd,001,00011,***456,Northwind,002,00022,***0103,10.00");

    publish(
        Topics.REJECTED,
        batchId.toString(),
        rejected,
        EventType.INSTRUCTION_REJECTED,
        batchId.toString(),
        t0);
    publish(
        Topics.REJECTED,
        batchId.toString(),
        rejected,
        EventType.INSTRUCTION_REJECTED,
        batchId.toString(),
        t0);
    publishBatch(TestEvents.batch(batchId, BatchStatus.RECEIVED, t0), null);

    List<AuditEvent> audit = eventually(() -> atLeast(auditEvents(batchId.toString()), 3));
    assertThat(audit)
        .filteredOn(e -> e.eventType() == AuditEventType.INSTRUCTION_REJECTED)
        .hasSize(2)
        .extracting(e -> e.details().get("readModelUpdated"))
        .containsExactlyInAnyOrder(true, false);
    mvc.perform(
            get("/api/v1/batches/" + batchId + "/rejections").header("Authorization", operator()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].lineNumber").value(4))
        .andExpect(jsonPath("$.content[0].instructionRef").value("ACME-0103"))
        .andExpect(jsonPath("$.content[0].reasonCodes[0]").value("INVALID_ACCOUNT"));
  }

  @Test
  void claimingANewInstructionStoresTheStatusTimeInUtc() {
    PaymentInstruction instruction = TestEvents.instruction(UUID.randomUUID(), 0);
    Instant at = Instant.parse("2026-10-15T13:30:00.123Z");

    transactions.executeWithoutResult(
        tx ->
            instructions.claim(
                instruction.idempotencyKey(),
                instruction.batchId(),
                InstructionStatus.ROUTED,
                null,
                at.plusNanos(456_789)));

    assertThat(
            jdbc.queryForObject(
                "SELECT DATE_FORMAT(updated_at, '%Y-%m-%dT%H:%i:%s.%f') FROM payment_instruction"
                    + " WHERE idempotency_key = ?",
                String.class, instruction.idempotencyKey()))
        .isEqualTo("2026-10-15T13:30:00.123000");
  }

  private void publishBatch(BatchEvent event, org.apache.kafka.common.header.Header reasonCode) {
    EventType type =
        event.status() == BatchStatus.RECEIVED
            ? EventType.BATCH_RECEIVED
            : EventType.BATCH_COMPLETED;
    if (reasonCode == null) {
      publish(
          Topics.BATCH_EVENTS,
          event.batchId().toString(),
          event,
          type,
          event.batchId().toString(),
          event.occurredAt());
    } else {
      publish(
          Topics.BATCH_EVENTS,
          event.batchId().toString(),
          event,
          type,
          event.batchId().toString(),
          event.occurredAt(),
          reasonCode);
    }
  }

  private void publishStatus(
      UUID batchId, String key, InstructionStatus status, String detail, Instant occurredAt) {
    publish(
        Topics.STATUS_EVENTS,
        key,
        new InstructionStatusEvent(key, batchId, status, Channel.EFT, detail, occurredAt),
        EventType.INSTRUCTION_STATUS,
        batchId.toString(),
        occurredAt);
  }

  private static <T> List<T> atLeast(List<T> items, int count) {
    return items.size() >= count ? items : null;
  }
}

package ca.musakaleem.remit.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.musakaleem.remit.api.audit.AuditEntityType;
import ca.musakaleem.remit.api.audit.AuditEvent;
import ca.musakaleem.remit.api.audit.AuditEventType;
import ca.musakaleem.remit.api.support.AbstractApiIT;
import ca.musakaleem.remit.api.support.TestEvents;
import ca.musakaleem.remit.api.support.TopicReader;
import ca.musakaleem.remit.common.RemitJson;
import ca.musakaleem.remit.common.Topics;
import ca.musakaleem.remit.common.event.Channel;
import ca.musakaleem.remit.common.event.EventHeaders;
import ca.musakaleem.remit.common.event.EventType;
import ca.musakaleem.remit.common.event.InstructionStatus;
import ca.musakaleem.remit.common.event.InstructionStatusEvent;
import ca.musakaleem.remit.common.event.PaymentInstruction;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * The dead-letter topic end to end: DLT records (instructions and poison pills) become dead letters
 * once, a replay republishes the instruction as intake would, and status events from routing settle
 * the replay.
 */
class DeadLetterReplayIT extends AbstractApiIT {

  private static final String ROUTING_EXCEPTION =
      "ca.musakaleem.remit.routing.error.RoutingException";

  /** Distinct fake source offsets, so tests never share a dedupe key. */
  private static final AtomicLong OFFSETS = new AtomicLong(System.nanoTime() % 1_000_000_000L);

  private final ObjectMapper json = RemitJson.objectMapper();

  @Test
  void aDeadLetteredInstructionIsStoredOnceAndMarksTheInstruction() throws Exception {
    PaymentInstruction instruction = TestEvents.instruction(UUID.randomUUID(), 0);
    long offset = OFFSETS.incrementAndGet();

    publishInstructionDeadLetter(instruction, offset);
    Map<String, Object> row = awaitDeadLetter(instruction.idempotencyKey());
    publishInstructionDeadLetter(instruction, offset);
    publishInstructionDeadLetter(instruction, offset);
    // A later record on the same DLT partition proves the two copies were consumed.
    PaymentInstruction marker = TestEvents.instruction(UUID.randomUUID(), 0);
    publishInstructionDeadLetter(marker, OFFSETS.incrementAndGet());
    awaitDeadLetter(marker.idempotencyKey());

    assertThat(deadLetterCount(instruction.idempotencyKey())).isEqualTo(1);
    assertThat(row.get("status")).isEqualTo("PENDING");
    assertThat(row.get("replay_count")).isEqualTo(0);
    assertThat(row.get("exception_class")).isEqualTo(ROUTING_EXCEPTION);
    List<AuditEvent> audit =
        auditEvents(instruction.idempotencyKey()).stream()
            .filter(e -> e.eventType() == AuditEventType.DEAD_LETTER_RECORDED)
            .toList();
    assertThat(audit)
        .singleElement()
        .satisfies(
            e -> {
              assertThat(e.actor()).isEqualTo("system:api-service");
              assertThat(e.details()).containsEntry("outcome", "CREATED");
              assertThat(e.details()).containsEntry("originalOffset", offset);
            });
    mvc.perform(
            get("/api/v1/instructions/" + instruction.idempotencyKey())
                .header("Authorization", operator()))
        .andExpect(jsonPath("$.status").value("DEAD_LETTERED"))
        .andExpect(jsonPath("$.statusDetail").value("Forced failure (chaos hook)"))
        .andExpect(jsonPath("$.debtor.account").value("******1234"));
    mvc.perform(
            get("/api/v1/dead-letters")
                .param("status", "PENDING")
                .header("Authorization", operator()))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath(
                String.format(
                    "$.content[?(@.idempotencyKey == '%s')].replayable",
                    instruction.idempotencyKey()),
                Matchers.contains(true)));
  }

  @Test
  void aPoisonPillIsStoredWithoutAKeyAndCannotBeReplayed() throws Exception {
    UUID batchId = UUID.randomUUID();
    long offset = OFFSETS.incrementAndGet();

    publishDeadLetter(
        "{not json".getBytes(StandardCharsets.UTF_8),
        false,
        batchId.toString(),
        1,
        offset,
        "org.springframework.kafka.support.serializer.DeserializationException",
        "failed to deserialize; nested exception is JsonParseException");

    Map<String, Object> row =
        eventually(
            () ->
                jdbc
                    .queryForList(
                        "SELECT id, idempotency_key, batch_id, payload, status FROM dead_letter"
                            + " WHERE original_offset = ?",
                        offset)
                    .stream()
                    .findFirst()
                    .orElse(null));
    assertThat(row.get("idempotency_key")).isNull();
    assertThat(row.get("batch_id")).isEqualTo(batchId.toString());
    assertThat(json.readTree((String) row.get("payload")).path("unreadable").asText())
        .isEqualTo("{not json");
    long id = ((Number) row.get("id")).longValue();
    assertThat(auditEvents(String.valueOf(id)))
        .singleElement()
        .satisfies(e -> assertThat(e.entityType()).isEqualTo(AuditEntityType.DEAD_LETTER));
    mvc.perform(post("/api/v1/dead-letters/" + id + "/replay").header("Authorization", admin()))
        .andExpect(status().isConflict())
        .andExpect(
            jsonPath("$.detail")
                .value("Dead letter " + id + " holds an unreadable record; it cannot be replayed"));
  }

  @Test
  void aReplayIsPublishedLikeIntakeAndSettledByRoutingStatusEvents() throws Exception {
    PaymentInstruction instruction = TestEvents.instruction(UUID.randomUUID(), 0);
    String key = instruction.idempotencyKey();
    publishInstructionDeadLetter(instruction, OFFSETS.incrementAndGet());
    long id = ((Number) awaitDeadLetter(key).get("id")).longValue();

    JsonNode response =
        json.readTree(
            mvc.perform(
                    post("/api/v1/dead-letters/" + id + "/replay").header("Authorization", admin()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.idempotencyKey").value(key))
                .andExpect(jsonPath("$.status").value("REPLAY_REQUESTED"))
                .andExpect(jsonPath("$.replayCount").value(1))
                .andReturn()
                .getResponse()
                .getContentAsString());

    ConsumerRecord<String, String> replayed =
        eventually(
            () ->
                TopicReader.byCorrelationId(Topics.INSTRUCTIONS, instruction.batchId().toString())
                    .stream()
                    .findFirst()
                    .orElse(null));
    PaymentInstruction republished = json.readValue(replayed.value(), PaymentInstruction.class);
    assertThat(republished.replayCount()).isEqualTo(1);
    assertThat(republished.idempotencyKey()).isEqualTo(key);
    assertThat(republished.debtor().account()).isEqualTo(TestEvents.DEBTOR_ACCOUNT);
    assertThat(replayed.key()).isEqualTo(TestEvents.DEBTOR_ACCOUNT);
    assertThat(header(replayed, "__TypeId__")).isEqualTo(PaymentInstruction.class.getName());
    assertThat(header(replayed, EventHeaders.EVENT_TYPE)).isEqualTo("PAYMENT_INSTRUCTION");
    assertThat(header(replayed, EventHeaders.CORRELATION_ID))
        .isEqualTo(instruction.batchId().toString());
    assertThat(header(replayed, EventHeaders.SCHEMA_VERSION)).isEqualTo("1");
    assertThat(UUID.fromString(header(replayed, EventHeaders.EVENT_ID))).isNotNull();
    assertThat(Instant.parse(header(replayed, EventHeaders.OCCURRED_AT)))
        .isEqualTo(Instant.parse(response.path("requestedAt").asText()));
    assertThat(deadLetterStatus(id)).isEqualTo("REPLAY_REQUESTED");

    AuditEvent audit =
        auditEvents(key).stream()
            .filter(e -> e.eventType() == AuditEventType.DLT_REPLAY_REQUESTED)
            .findFirst()
            .orElseThrow();
    assertThat(audit.actor()).isEqualTo("user:admin");
    assertThat(audit.correlationId()).isEqualTo(instruction.batchId().toString());
    assertThat(audit.details()).containsEntry("deadLetterId", id).containsEntry("replayCount", 1);
    mvc.perform(
            get("/api/v1/audit-events")
                .param("entityId", key)
                .param("eventType", "DLT_REPLAY_REQUESTED")
                .header("Authorization", admin()))
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].actor").value("user:admin"));

    // routing-service processes the replay: REPLAYED then ROUTED, same millisecond.
    Instant routedAt = Instant.now();
    publishStatus(instruction, InstructionStatus.REPLAYED, "replay 1", routedAt);
    publishStatus(instruction, InstructionStatus.ROUTED, null, routedAt);

    eventually(() -> "REPLAYED".equals(deadLetterStatus(id)) ? true : null);
    mvc.perform(get("/api/v1/instructions/" + key).header("Authorization", operator()))
        .andExpect(jsonPath("$.status").value("ROUTED"))
        .andExpect(jsonPath("$.history[*].status", Matchers.contains("REPLAYED", "ROUTED")));
    mvc.perform(post("/api/v1/dead-letters/" + id + "/replay").header("Authorization", admin()))
        .andExpect(status().isConflict())
        .andExpect(
            jsonPath("$.detail").value("Dead letter " + id + " was already replayed successfully"));
  }

  @Test
  void theFourthReplayIs409AndTheDeadLetterIsExhausted() throws Exception {
    PaymentInstruction instruction = TestEvents.instruction(UUID.randomUUID(), 0);
    publishInstructionDeadLetter(instruction, OFFSETS.incrementAndGet());
    long id = ((Number) awaitDeadLetter(instruction.idempotencyKey()).get("id")).longValue();

    for (int replay = 1; replay <= 3; replay++) {
      mvc.perform(post("/api/v1/dead-letters/" + id + "/replay").header("Authorization", admin()))
          .andExpect(status().isAccepted())
          .andExpect(jsonPath("$.replayCount").value(replay));
      mvc.perform(post("/api/v1/dead-letters/" + id + "/replay").header("Authorization", admin()))
          .andExpect(status().isConflict())
          .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
          .andExpect(jsonPath("$.detail").value(Matchers.containsString("already pending")));
      // routing-service fails the replay again.
      publishStatus(instruction, InstructionStatus.DEAD_LETTERED, "Forced failure", Instant.now());
      String expected = replay < 3 ? "PENDING" : "EXHAUSTED";
      eventually(() -> expected.equals(deadLetterStatus(id)) ? true : null);
    }

    mvc.perform(post("/api/v1/dead-letters/" + id + "/replay").header("Authorization", admin()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.title").value("Conflict"))
        .andExpect(
            jsonPath("$.detail").value("Dead letter " + id + " reached the limit of 3 replays"));
    assertThat(deadLetterStatus(id)).isEqualTo("EXHAUSTED");
    assertThat(
            jdbc.queryForObject(
                "SELECT replay_count FROM dead_letter WHERE id = ?", Integer.class, id))
        .isEqualTo(3);
  }

  @Test
  void aRecordAlreadyReplayedThreeTimesIsExhaustedOnArrivalAndRefused() throws Exception {
    PaymentInstruction instruction = TestEvents.instruction(UUID.randomUUID(), 3);
    publishInstructionDeadLetter(instruction, OFFSETS.incrementAndGet());
    Map<String, Object> row = awaitDeadLetter(instruction.idempotencyKey());

    assertThat(row.get("status")).isEqualTo("EXHAUSTED");
    mvc.perform(
            post("/api/v1/dead-letters/" + row.get("id") + "/replay")
                .header("Authorization", admin()))
        .andExpect(status().isConflict());
  }

  @Test
  void theDltRecordOfAFailedReplayUpdatesTheSameDeadLetter() throws Exception {
    PaymentInstruction instruction = TestEvents.instruction(UUID.randomUUID(), 0);
    publishInstructionDeadLetter(instruction, OFFSETS.incrementAndGet());
    long id = ((Number) awaitDeadLetter(instruction.idempotencyKey()).get("id")).longValue();
    mvc.perform(post("/api/v1/dead-letters/" + id + "/replay").header("Authorization", admin()))
        .andExpect(status().isAccepted());

    PaymentInstruction replay1 = withReplayCount(instruction, 1);
    publishInstructionDeadLetter(replay1, OFFSETS.incrementAndGet());

    eventually(() -> "PENDING".equals(deadLetterStatus(id)) ? true : null);
    assertThat(deadLetterCount(instruction.idempotencyKey())).isEqualTo(1);
    assertThat(
            auditEvents(instruction.idempotencyKey()).stream()
                .filter(e -> e.eventType() == AuditEventType.DEAD_LETTER_RECORDED)
                .map(e -> e.details().get("outcome")))
        .containsExactly("CREATED", "FAILED_AGAIN");
  }

  @Test
  void anUnknownDeadLetterIs404() throws Exception {
    mvc.perform(post("/api/v1/dead-letters/999999999/replay").header("Authorization", admin()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.detail").value("Dead letter 999999999 not found"));
  }

  private void publishInstructionDeadLetter(PaymentInstruction instruction, long offset)
      throws Exception {
    publishDeadLetter(
        json.writeValueAsBytes(instruction),
        true,
        instruction.batchId().toString(),
        1,
        offset,
        ROUTING_EXCEPTION,
        "Forced failure (chaos hook)");
  }

  private void publishStatus(
      PaymentInstruction instruction, InstructionStatus status, String detail, Instant at) {
    publish(
        Topics.STATUS_EVENTS,
        instruction.idempotencyKey(),
        new InstructionStatusEvent(
            instruction.idempotencyKey(), instruction.batchId(), status, Channel.EFT, detail, at),
        EventType.INSTRUCTION_STATUS,
        instruction.batchId().toString(),
        at);
  }

  private Map<String, Object> awaitDeadLetter(String key) {
    return eventually(
        () ->
            jdbc
                .queryForList(
                    "SELECT id, status, replay_count, exception_class FROM dead_letter"
                        + " WHERE idempotency_key = ?",
                    key)
                .stream()
                .findFirst()
                .orElse(null));
  }

  private int deadLetterCount(String key) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM dead_letter WHERE idempotency_key = ?", Integer.class, key);
  }

  private String deadLetterStatus(long id) {
    return jdbc.queryForObject("SELECT status FROM dead_letter WHERE id = ?", String.class, id);
  }

  private static String header(ConsumerRecord<String, String> record, String name) {
    return EventHeaders.lastValue(record.headers(), name).orElse(null);
  }

  private static PaymentInstruction withReplayCount(PaymentInstruction source, int replayCount) {
    return new PaymentInstruction(
        source.idempotencyKey(),
        source.batchId(),
        source.clientId(),
        source.instructionRef(),
        source.debtor(),
        source.creditor(),
        source.amount(),
        source.currency(),
        source.channel(),
        source.valueDate(),
        source.remittanceInfo(),
        replayCount);
  }
}

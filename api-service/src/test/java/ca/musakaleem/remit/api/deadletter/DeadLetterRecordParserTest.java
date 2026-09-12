package ca.musakaleem.remit.api.deadletter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.musakaleem.remit.api.support.TestEvents;
import ca.musakaleem.remit.common.RemitJson;
import ca.musakaleem.remit.common.event.PaymentInstruction;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.record.TimestampType;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.KafkaHeaders;

class DeadLetterRecordParserTest {

  private static final long TIMESTAMP = 1_792_000_000_000L;

  private final DeadLetterRecordParser parser = new DeadLetterRecordParser();
  private final ObjectMapper json = RemitJson.objectMapper();
  private final UUID batchId = UUID.randomUUID();

  @Test
  void readsARoutingFailureWithItsOriginAndCause() throws Exception {
    PaymentInstruction instruction = TestEvents.instruction(batchId, 1);
    RecordHeaders headers = originHeaders(2, 1234L);
    headers.add(text(KafkaHeaders.DLT_EXCEPTION_FQCN, "o.s.k.ListenerExecutionFailedException"));
    headers.add(
        text(
            KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN,
            "ca.musakaleem.remit.routing.error.RoutingException"));
    headers.add(
        text(
            KafkaHeaders.DLT_EXCEPTION_MESSAGE,
            "Listener failed; Forced failure for debtor 1000001234"));
    headers.add(text("correlationId", batchId.toString()));

    DeadLetterRecord parsed = parser.parse(record(json.writeValueAsBytes(instruction), headers));

    assertThat(parsed.readable()).isTrue();
    assertThat(parsed.instruction()).isEqualTo(instruction);
    assertThat(parsed.originalTopic()).isEqualTo("payments.instructions");
    assertThat(parsed.originalPartition()).isEqualTo(2);
    assertThat(parsed.originalOffset()).isEqualTo(1234L);
    assertThat(parsed.exceptionClass())
        .isEqualTo("ca.musakaleem.remit.routing.error.RoutingException");
    assertThat(parsed.exceptionMessage()).endsWith("debtor ******1234");
    assertThat(parsed.deadLetteredAt().toEpochMilli()).isEqualTo(TIMESTAMP);
    assertThat(parsed.correlationId()).isEqualTo(batchId.toString());
    assertThat(parser.readStored(parsed.payloadJson())).isEqualTo(instruction);
  }

  @Test
  void keepsAPoisonPillAsMaskedTextInAJsonObject() throws Exception {
    byte[] poison = "{not json 1000001234".getBytes(StandardCharsets.UTF_8);
    RecordHeaders headers = originHeaders(0, 5L);
    headers.add(
        text(
            KafkaHeaders.DLT_EXCEPTION_FQCN,
            "org.springframework.kafka.support.serializer.DeserializationException"));

    DeadLetterRecord parsed = parser.parse(record(poison, headers));

    JsonNode payload = json.readTree(parsed.payloadJson());
    assertThat(parsed.readable()).isFalse();
    assertThat(parsed.exceptionClass()).endsWith("DeserializationException");
    assertThat(payload.path("unreadable").asText()).isEqualTo("{not json ******1234");
    assertThat(payload.path("sizeBytes").asInt()).isEqualTo(poison.length);
  }

  @Test
  void treatsJsonThatIsNotAnInstructionAsUnreadable() {
    DeadLetterRecord otherType =
        parser.parse(
            record(
                "{\"batchId\":\"x\",\"status\":\"RECEIVED\"}".getBytes(StandardCharsets.UTF_8),
                originHeaders(0, 1L)));
    DeadLetterRecord badKey =
        parser.parse(
            record(
                ("{\"idempotencyKey\":\"short\",\"batchId\":\"" + batchId + "\"}")
                    .getBytes(StandardCharsets.UTF_8),
                originHeaders(0, 2L)));
    DeadLetterRecord empty = parser.parse(record(null, originHeaders(0, 3L)));

    assertThat(otherType.readable()).isFalse();
    assertThat(badKey.readable()).isFalse();
    assertThat(empty.readable()).isFalse();
    assertThat(empty.payloadJson()).contains("\"sizeBytes\":0");
  }

  @Test
  void fallsBackToTheRecordsOwnCoordinatesWithoutOriginHeaders() {
    DeadLetterRecord parsed = parser.parse(record(new byte[] {1, 2}, new RecordHeaders()));

    assertThat(parsed.originalTopic()).isEqualTo("payments.instructions.DLT");
    assertThat(parsed.originalPartition()).isEqualTo(1);
    assertThat(parsed.originalOffset()).isEqualTo(77L);
    assertThat(parsed.exceptionClass()).isNull();
    assertThat(parsed.exceptionMessage()).isNull();
  }

  @Test
  void cutsLongMessagesAndPayloads() {
    RecordHeaders headers = originHeaders(0, 1L);
    headers.add(text(KafkaHeaders.DLT_EXCEPTION_MESSAGE, "m".repeat(5000)));

    DeadLetterRecord parsed =
        parser.parse(record("x".repeat(10_000).getBytes(StandardCharsets.UTF_8), headers));

    assertThat(parsed.exceptionMessage()).hasSize(DeadLetterRecordParser.MAX_MESSAGE_LENGTH);
    assertThat(parsed.payloadJson().length()).isLessThan(4100);
  }

  @Test
  void theAuditIdDependsOnlyOnTheOrigin() {
    DeadLetterRecord first = parser.parse(record(null, originHeaders(1, 9L)));
    DeadLetterRecord again = parser.parse(record(new byte[] {1}, originHeaders(1, 9L)));
    DeadLetterRecord other = parser.parse(record(null, originHeaders(1, 10L)));

    assertThat(first.auditEventId()).isEqualTo(again.auditEventId());
    assertThat(first.auditEventId()).isNotEqualTo(other.auditEventId());
  }

  @Test
  void refusesAStoredPayloadThatIsNotJson() {
    assertThatThrownBy(() -> parser.readStored("{not json"))
        .isInstanceOf(IllegalStateException.class);
  }

  private static ConsumerRecord<String, byte[]> record(byte[] value, RecordHeaders headers) {
    return new ConsumerRecord<>(
        "payments.instructions.DLT",
        1,
        77L,
        TIMESTAMP,
        TimestampType.CREATE_TIME,
        0,
        value == null ? 0 : value.length,
        TestEvents.DEBTOR_ACCOUNT,
        value,
        headers,
        Optional.empty());
  }

  private static RecordHeaders originHeaders(int partition, long offset) {
    RecordHeaders headers = new RecordHeaders();
    headers.add(text(KafkaHeaders.DLT_ORIGINAL_TOPIC, "payments.instructions"));
    headers.add(
        new RecordHeader(
            KafkaHeaders.DLT_ORIGINAL_PARTITION,
            ByteBuffer.allocate(Integer.BYTES).putInt(partition).array()));
    headers.add(
        new RecordHeader(
            KafkaHeaders.DLT_ORIGINAL_OFFSET,
            ByteBuffer.allocate(Long.BYTES).putLong(offset).array()));
    return headers;
  }

  private static RecordHeader text(String name, String value) {
    return new RecordHeader(name, value.getBytes(StandardCharsets.UTF_8));
  }
}

package ca.musakaleem.remit.common.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;

class EventHeadersTest {

  private static final Instant OCCURRED_AT = Instant.parse("2026-10-15T13:30:00.123Z");
  private static final String BATCH_ID = "7f1c9a5e-2d3b-4c8e-9f10-1a2b3c4d5e6f";

  @Test
  void buildsTheFiveStandardHeadersInOrder() {
    UUID eventId = UUID.fromString("00000000-0000-0000-0000-000000000001");

    List<Header> headers =
        EventHeaders.build(eventId, EventType.BATCH_RECEIVED, BATCH_ID, OCCURRED_AT);

    assertThat(headers)
        .extracting(Header::key)
        .containsExactly("eventId", "eventType", "occurredAt", "correlationId", "schemaVersion");
    assertThat(headers)
        .extracting(h -> new String(h.value(), StandardCharsets.UTF_8))
        .containsExactly(
            eventId.toString(), "BATCH_RECEIVED", "2026-10-15T13:30:00.123Z", BATCH_ID, "1");
  }

  @Test
  void generatesADistinctEventIdPerCall() {
    RecordHeaders first =
        new RecordHeaders(EventHeaders.build(EventType.PAYMENT_INSTRUCTION, BATCH_ID, OCCURRED_AT));
    RecordHeaders second =
        new RecordHeaders(EventHeaders.build(EventType.PAYMENT_INSTRUCTION, BATCH_ID, OCCURRED_AT));

    String firstId = EventHeaders.lastValue(first, EventHeaders.EVENT_ID).orElseThrow();
    String secondId = EventHeaders.lastValue(second, EventHeaders.EVENT_ID).orElseThrow();

    assertThat(UUID.fromString(firstId)).isNotEqualTo(UUID.fromString(secondId));
  }

  @Test
  void recordCarriesTopicKeyValueAndHeaders() {
    ProducerRecord<String, String> record =
        EventHeaders.record(
            "payments.rejected",
            "key-1",
            "payload",
            EventType.INSTRUCTION_REJECTED,
            BATCH_ID,
            OCCURRED_AT);

    assertThat(record.topic()).isEqualTo("payments.rejected");
    assertThat(record.key()).isEqualTo("key-1");
    assertThat(record.value()).isEqualTo("payload");
    assertThat(record.partition()).isNull();
    assertThat(EventHeaders.lastValue(record.headers(), EventHeaders.EVENT_TYPE))
        .contains("INSTRUCTION_REJECTED");
    assertThat(EventHeaders.lastValue(record.headers(), EventHeaders.CORRELATION_ID))
        .contains(BATCH_ID);
    assertThat(EventHeaders.lastValue(record.headers(), EventHeaders.SCHEMA_VERSION))
        .contains(EventHeaders.CURRENT_SCHEMA_VERSION);
  }

  @Test
  void lastValueIsEmptyForMissingOrNullHeader() {
    RecordHeaders headers = new RecordHeaders();
    headers.add("nullValued", null);

    assertThat(EventHeaders.lastValue(headers, "absent")).isEmpty();
    assertThat(EventHeaders.lastValue(headers, "nullValued")).isEmpty();
  }

  @Test
  void lastValueReturnsTheMostRecentHeader() {
    RecordHeaders headers = new RecordHeaders();
    headers.add(EventHeaders.header(EventHeaders.REASON_CODE, "EMPTY_FILE"));
    headers.add(EventHeaders.header(EventHeaders.REASON_CODE, "DUPLICATE_FILE"));

    assertThat(EventHeaders.lastValue(headers, EventHeaders.REASON_CODE))
        .contains("DUPLICATE_FILE");
  }

  @Test
  void rejectsNullArguments() {
    UUID id = UUID.randomUUID();
    assertThatNullPointerException()
        .isThrownBy(
            () -> EventHeaders.build(null, EventType.BATCH_COMPLETED, BATCH_ID, OCCURRED_AT));
    assertThatNullPointerException()
        .isThrownBy(() -> EventHeaders.build(id, null, BATCH_ID, OCCURRED_AT));
    assertThatNullPointerException()
        .isThrownBy(() -> EventHeaders.build(id, EventType.BATCH_COMPLETED, null, OCCURRED_AT));
    assertThatNullPointerException()
        .isThrownBy(() -> EventHeaders.build(id, EventType.BATCH_COMPLETED, BATCH_ID, null));
  }
}

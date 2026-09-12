package ca.musakaleem.remit.api.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.musakaleem.remit.common.event.BatchEvent;
import ca.musakaleem.remit.common.event.EventHeaders;
import ca.musakaleem.remit.common.event.EventType;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.record.TimestampType;
import org.junit.jupiter.api.Test;

class EventMetadataTest {

  private static final Instant T0 = Instant.parse("2026-10-15T13:30:00.123Z");

  @Test
  void readsTheStandardHeaders() {
    UUID eventId = UUID.randomUUID();
    RecordHeaders headers = new RecordHeaders();
    EventHeaders.build(eventId, EventType.BATCH_RECEIVED, "batch-1", T0).forEach(headers::add);

    EventMetadata meta = EventMetadata.of(record("value", headers));

    assertThat(meta.eventId()).isEqualTo(eventId);
    assertThat(meta.eventType()).isEqualTo("BATCH_RECEIVED");
    assertThat(meta.occurredAt()).isEqualTo(T0);
    assertThat(meta.correlationId()).isEqualTo("batch-1");
    assertThat(meta.topic()).isEqualTo("payments.batch-events");
    assertThat(meta.partition()).isEqualTo(2);
    assertThat(meta.offset()).isEqualTo(40L);
  }

  @Test
  void fallsBackToAStableIdAndTheRecordTimestamp() {
    RecordHeaders malformed = new RecordHeaders();
    malformed.add(new RecordHeader("eventId", "nope".getBytes(StandardCharsets.UTF_8)));
    malformed.add(new RecordHeader("occurredAt", "yesterday".getBytes(StandardCharsets.UTF_8)));

    EventMetadata first = EventMetadata.of(record("value", malformed));
    EventMetadata again = EventMetadata.of(record("value", new RecordHeaders()));

    assertThat(first.eventId()).isEqualTo(again.eventId());
    assertThat(first.occurredAt()).isEqualTo(Instant.ofEpochMilli(1_000L));
    assertThat(first.correlationId()).isNull();
  }

  @Test
  void refusesAnUnexpectedPayload() {
    ConsumerRecord<String, Object> wrong = record("not a batch event", new RecordHeaders());
    ConsumerRecord<String, Object> empty = record(null, new RecordHeaders());

    assertThatThrownBy(() -> EventMetadata.payload(wrong, BatchEvent.class))
        .isInstanceOf(UnexpectedPayloadException.class)
        .hasMessage("Expected a BatchEvent on payments.batch-events-2@40 but got java.lang.String");
    assertThatThrownBy(() -> EventMetadata.payload(empty, BatchEvent.class))
        .hasMessageEndingWith("but got null");
    assertThat(EventMetadata.payload(wrong, String.class)).isEqualTo("not a batch event");
  }

  private static ConsumerRecord<String, Object> record(Object value, RecordHeaders headers) {
    return new ConsumerRecord<>(
        "payments.batch-events",
        2,
        40L,
        1_000L,
        TimestampType.CREATE_TIME,
        0,
        0,
        "key",
        value,
        headers,
        Optional.empty());
  }
}

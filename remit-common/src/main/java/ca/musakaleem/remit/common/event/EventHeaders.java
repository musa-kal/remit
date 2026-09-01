package ca.musakaleem.remit.common.event;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeader;

/** Kafka header names every Remit event carries (spec section 5) and helpers to build them. */
public final class EventHeaders {

  /** Random UUID, unique per published event. */
  public static final String EVENT_ID = "eventId";

  /** One of the {@link EventType} names. */
  public static final String EVENT_TYPE = "eventType";

  /** ISO-8601 UTC instant at which the event happened. */
  public static final String OCCURRED_AT = "occurredAt";

  /** Batch id the event belongs to, used to correlate logs and audit entries. */
  public static final String CORRELATION_ID = "correlationId";

  /** Version of the event payload contract. */
  public static final String SCHEMA_VERSION = "schemaVersion";

  /** Optional: file-level reason code carried by a FAILED batch event. */
  public static final String REASON_CODE = "reasonCode";

  /** The payload contract version this code produces. */
  public static final String CURRENT_SCHEMA_VERSION = "1";

  private EventHeaders() {}

  /**
   * Builds the five standard headers with a fresh random event id.
   *
   * @param type event type
   * @param correlationId correlation id (the batch id)
   * @param occurredAt when the event happened
   * @return the headers, in a fixed order
   */
  public static List<Header> build(EventType type, String correlationId, Instant occurredAt) {
    return build(UUID.randomUUID(), type, correlationId, occurredAt);
  }

  /**
   * Builds the five standard headers.
   *
   * @param eventId unique event id
   * @param type event type
   * @param correlationId correlation id (the batch id)
   * @param occurredAt when the event happened
   * @return the headers, in a fixed order
   */
  public static List<Header> build(
      UUID eventId, EventType type, String correlationId, Instant occurredAt) {
    Objects.requireNonNull(eventId, "eventId");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(correlationId, "correlationId");
    Objects.requireNonNull(occurredAt, "occurredAt");
    return List.of(
        header(EVENT_ID, eventId.toString()),
        header(EVENT_TYPE, type.name()),
        header(OCCURRED_AT, occurredAt.toString()),
        header(CORRELATION_ID, correlationId),
        header(SCHEMA_VERSION, CURRENT_SCHEMA_VERSION));
  }

  /**
   * Creates a producer record carrying the five standard headers.
   *
   * @param topic target topic
   * @param key record key
   * @param value event payload
   * @param type event type
   * @param correlationId correlation id (the batch id)
   * @param occurredAt when the event happened
   * @param <K> key type
   * @param <V> value type
   * @return a record ready to send
   */
  public static <K, V> ProducerRecord<K, V> record(
      String topic, K key, V value, EventType type, String correlationId, Instant occurredAt) {
    return new ProducerRecord<>(topic, null, key, value, build(type, correlationId, occurredAt));
  }

  /**
   * Creates a single UTF-8 header.
   *
   * @param name header name
   * @param value header value
   * @return the header
   */
  public static Header header(String name, String value) {
    return new RecordHeader(name, value.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Reads the last value of a header as UTF-8 text.
   *
   * @param headers record headers
   * @param name header name
   * @return the value, or empty when the header is absent
   */
  public static Optional<String> lastValue(Headers headers, String name) {
    Header header = headers.lastHeader(name);
    if (header == null || header.value() == null) {
      return Optional.empty();
    }
    return Optional.of(new String(header.value(), StandardCharsets.UTF_8));
  }
}

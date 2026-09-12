package ca.musakaleem.remit.api.messaging;

import ca.musakaleem.remit.common.event.EventHeaders;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;

/**
 * The standard headers of a consumed event (spec section 5), with fallbacks for a record that lacks
 * them: an event id derived from topic, partition and offset (stable across redelivery) and the
 * record timestamp as occurrence time.
 *
 * @param eventId {@code eventId} header
 * @param eventType {@code eventType} header, may be {@code null}
 * @param occurredAt {@code occurredAt} header
 * @param correlationId {@code correlationId} header (batch id), may be {@code null}
 * @param topic source topic
 * @param partition source partition
 * @param offset source offset
 */
public record EventMetadata(
    UUID eventId,
    String eventType,
    Instant occurredAt,
    String correlationId,
    String topic,
    int partition,
    long offset) {

  /**
   * Reads the metadata of a record.
   *
   * @param record consumed record
   * @return the metadata
   */
  public static EventMetadata of(ConsumerRecord<?, ?> record) {
    UUID eventId =
        EventHeaders.lastValue(record.headers(), EventHeaders.EVENT_ID)
            .flatMap(EventMetadata::uuid)
            .orElseGet(
                () ->
                    UUID.nameUUIDFromBytes(
                        (record.topic() + ":" + record.partition() + ":" + record.offset())
                            .getBytes(StandardCharsets.UTF_8)));
    Instant occurredAt =
        EventHeaders.lastValue(record.headers(), EventHeaders.OCCURRED_AT)
            .flatMap(EventMetadata::instant)
            .orElseGet(() -> Instant.ofEpochMilli(record.timestamp()));
    return new EventMetadata(
        eventId,
        EventHeaders.lastValue(record.headers(), EventHeaders.EVENT_TYPE).orElse(null),
        occurredAt,
        EventHeaders.lastValue(record.headers(), EventHeaders.CORRELATION_ID).orElse(null),
        record.topic(),
        record.partition(),
        record.offset());
  }

  /**
   * Casts a record value to the expected event type.
   *
   * @param record consumed record
   * @param type expected type
   * @param <T> expected type
   * @return the value
   * @throws UnexpectedPayloadException when the value is missing or of another type
   */
  public static <T> T payload(ConsumerRecord<?, ?> record, Class<T> type) {
    Object value = record.value();
    if (!type.isInstance(value)) {
      throw new UnexpectedPayloadException(
          "Expected a "
              + type.getSimpleName()
              + " on "
              + record.topic()
              + "-"
              + record.partition()
              + "@"
              + record.offset()
              + " but got "
              + (value == null ? "null" : value.getClass().getName()));
    }
    return type.cast(value);
  }

  private static Optional<UUID> uuid(String value) {
    try {
      return Optional.of(UUID.fromString(value));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  private static Optional<Instant> instant(String value) {
    try {
      return Optional.of(Instant.parse(value));
    } catch (DateTimeParseException e) {
      return Optional.empty();
    }
  }
}

package ca.musakaleem.remit.common;

import java.util.Map;

/**
 * Spring Kafka JSON (de)serializer property names and values shared by every service, kept as plain
 * strings so this module does not depend on Spring.
 */
public final class KafkaJson {

  /** The only package consumers trust when reading type headers (spec section 5). */
  public static final String TRUSTED_PACKAGES = "ca.musakaleem.remit.common.event";

  /** Spring Kafka {@code JsonSerializer} class name. */
  public static final String JSON_SERIALIZER =
      "org.springframework.kafka.support.serializer.JsonSerializer";

  /** Spring Kafka {@code JsonDeserializer} class name. */
  public static final String JSON_DESERIALIZER =
      "org.springframework.kafka.support.serializer.JsonDeserializer";

  /** Spring Kafka {@code ErrorHandlingDeserializer} class name (wraps the JSON deserializer). */
  public static final String ERROR_HANDLING_DESERIALIZER =
      "org.springframework.kafka.support.serializer.ErrorHandlingDeserializer";

  /** Property: comma separated packages the JSON deserializer may instantiate. */
  public static final String TRUSTED_PACKAGES_PROPERTY = "spring.json.trusted.packages";

  /** Property: whether the JSON serializer adds {@code __TypeId__} headers. */
  public static final String ADD_TYPE_HEADERS_PROPERTY = "spring.json.add.type.headers";

  /** Property: whether the JSON deserializer uses {@code __TypeId__} headers. */
  public static final String USE_TYPE_HEADERS_PROPERTY = "spring.json.use.type.headers";

  /** Property: fallback value type when no type header is present. */
  public static final String VALUE_DEFAULT_TYPE_PROPERTY = "spring.json.value.default.type";

  /** Property: delegate value deserializer used by {@code ErrorHandlingDeserializer}. */
  public static final String VALUE_DELEGATE_PROPERTY = "spring.deserializer.value.delegate.class";

  private KafkaJson() {}

  /**
   * Producer properties: JSON values with type headers, so consumers know the event class.
   *
   * @return immutable property map to merge into producer config
   */
  public static Map<String, Object> producerProperties() {
    return Map.of("value.serializer", JSON_SERIALIZER, ADD_TYPE_HEADERS_PROPERTY, true);
  }

  /**
   * Consumer properties: JSON values wrapped in the error handling deserializer, trusting only the
   * Remit event package.
   *
   * @return immutable property map to merge into consumer config
   */
  public static Map<String, Object> consumerProperties() {
    return Map.of(
        "value.deserializer",
        ERROR_HANDLING_DESERIALIZER,
        VALUE_DELEGATE_PROPERTY,
        JSON_DESERIALIZER,
        TRUSTED_PACKAGES_PROPERTY,
        TRUSTED_PACKAGES,
        USE_TYPE_HEADERS_PROPERTY,
        true);
  }
}

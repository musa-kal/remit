package ca.musakaleem.remit.common;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/** Shared Jackson settings so every service writes and reads event JSON the same way. */
public final class RemitJson {

  private RemitJson() {}

  /**
   * Creates a new mapper with the Remit settings applied.
   *
   * @return a configured mapper
   */
  public static ObjectMapper objectMapper() {
    return configure(new ObjectMapper());
  }

  /**
   * Applies the Remit settings to an existing mapper: {@code java.time} support with ISO-8601
   * strings instead of numeric arrays, plain (non-scientific) decimals, and tolerance of unknown
   * properties so an older consumer can read a newer event.
   *
   * @param mapper the mapper to configure
   * @return the same mapper, for chaining
   */
  public static ObjectMapper configure(ObjectMapper mapper) {
    mapper.registerModule(new JavaTimeModule());
    mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    mapper.disable(SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS);
    mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    mapper.enable(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN);
    return mapper;
  }
}

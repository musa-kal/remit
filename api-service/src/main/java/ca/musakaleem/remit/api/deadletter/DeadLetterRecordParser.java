package ca.musakaleem.remit.api.deadletter;

import ca.musakaleem.remit.common.MaskingUtil;
import ca.musakaleem.remit.common.RemitJson;
import ca.musakaleem.remit.common.event.EventHeaders;
import ca.musakaleem.remit.common.event.PaymentInstruction;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.regex.Pattern;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Component;

/**
 * Parses a raw DLT record (docs/decisions/0003-dlt-and-replay.md). The value is either a {@code
 * PaymentInstruction} as JSON (a routing failure) or the original bytes of a record that could not
 * be deserialized (a poison pill), so it is read as bytes and parsed here. The binary Spring
 * headers {@code kafka_dlt-original-partition} (4-byte int) and {@code kafka_dlt-original-offset}
 * (8-byte long) are big-endian.
 */
@Component
public class DeadLetterRecordParser {

  /** Longest exception message stored. */
  static final int MAX_MESSAGE_LENGTH = 1000;

  /** Longest excerpt of an unreadable payload stored. */
  static final int MAX_UNREADABLE_LENGTH = 4000;

  private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[0-9a-f]{64}");

  private final ObjectMapper json = RemitJson.objectMapper();

  /**
   * Parses a DLT record.
   *
   * @param record the record with raw byte value
   * @return the parsed record
   */
  public DeadLetterRecord parse(ConsumerRecord<String, byte[]> record) {
    Headers headers = record.headers();
    String originalTopic =
        EventHeaders.lastValue(headers, KafkaHeaders.DLT_ORIGINAL_TOPIC).orElse(record.topic());
    int originalPartition =
        bytes(headers, KafkaHeaders.DLT_ORIGINAL_PARTITION)
            .filter(b -> b.length == Integer.BYTES)
            .map(b -> ByteBuffer.wrap(b).getInt())
            .orElse(record.partition());
    long originalOffset =
        bytes(headers, KafkaHeaders.DLT_ORIGINAL_OFFSET)
            .filter(b -> b.length == Long.BYTES)
            .map(b -> ByteBuffer.wrap(b).getLong())
            .orElse(record.offset());
    String exceptionClass =
        EventHeaders.lastValue(headers, KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN)
            .or(() -> EventHeaders.lastValue(headers, KafkaHeaders.DLT_EXCEPTION_FQCN))
            .orElse(null);
    String exceptionMessage =
        EventHeaders.lastValue(headers, KafkaHeaders.DLT_EXCEPTION_MESSAGE)
            .map(MaskingUtil::maskAccountNumbers)
            .map(m -> cut(m, MAX_MESSAGE_LENGTH))
            .orElse(null);
    PaymentInstruction instruction = readInstruction(record.value());
    return new DeadLetterRecord(
        originalTopic,
        originalPartition,
        originalOffset,
        exceptionClass,
        exceptionMessage,
        instruction,
        instruction != null ? write(instruction) : describeUnreadable(record.value()),
        Instant.ofEpochMilli(record.timestamp()),
        EventHeaders.lastValue(headers, EventHeaders.CORRELATION_ID).orElse(null));
  }

  /**
   * Reads a stored payload back as an instruction, for a replay.
   *
   * @param payloadJson the stored JSON
   * @return the instruction
   */
  public PaymentInstruction readStored(String payloadJson) {
    try {
      return json.readValue(payloadJson, PaymentInstruction.class);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Stored dead-letter payload is not an instruction", e);
    }
  }

  /**
   * An instruction is readable only if it has a well formed key and a batch id; any other JSON (for
   * example an object of a type routing-service refused) is treated as unreadable.
   */
  private PaymentInstruction readInstruction(byte[] value) {
    if (value == null || value.length == 0) {
      return null;
    }
    try {
      PaymentInstruction instruction = json.readValue(value, PaymentInstruction.class);
      boolean usable =
          instruction != null
              && instruction.idempotencyKey() != null
              && IDEMPOTENCY_KEY.matcher(instruction.idempotencyKey()).matches()
              && instruction.batchId() != null;
      return usable ? instruction : null;
    } catch (IOException e) {
      return null;
    }
  }

  private String write(PaymentInstruction instruction) {
    try {
      return json.writeValueAsString(instruction);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Cannot write instruction JSON", e);
    }
  }

  private String describeUnreadable(byte[] value) {
    String text = value == null ? "" : new String(value, StandardCharsets.UTF_8);
    ObjectNode node = json.createObjectNode();
    node.put("unreadable", cut(MaskingUtil.maskAccountNumbers(text), MAX_UNREADABLE_LENGTH));
    node.put("sizeBytes", value == null ? 0 : value.length);
    return node.toString();
  }

  private static Optional<byte[]> bytes(Headers headers, String name) {
    Header header = headers.lastHeader(name);
    return header == null ? Optional.empty() : Optional.ofNullable(header.value());
  }

  private static String cut(String text, int max) {
    return text.length() <= max ? text : text.substring(0, max);
  }
}

package ca.musakaleem.remit.routing.support;

import static org.awaitility.Awaitility.await;

import ca.musakaleem.remit.common.RemitJson;
import ca.musakaleem.remit.common.Topics;
import ca.musakaleem.remit.common.event.EventHeaders;
import ca.musakaleem.remit.common.event.EventType;
import ca.musakaleem.remit.common.event.InstructionStatus;
import ca.musakaleem.remit.common.event.InstructionStatusEvent;
import ca.musakaleem.remit.common.event.PaymentInstruction;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base class for routing integration tests: the full application on a random port against the
 * shared Testcontainers MySQL and Kafka, with the FR-RTE-04 chaos hook switched on. Every test uses
 * fresh instruction keys and batch ids, so tests never see each other's records.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "remit.routing.chaos-enabled=true")
@ActiveProfiles("test")
public abstract class AbstractRoutingIT {

  /** Upper bound for asynchronous outcomes; retries alone take 3 seconds. */
  protected static final Duration WAIT = Duration.ofSeconds(60);

  private static final ObjectMapper JSON = RemitJson.objectMapper();

  @Autowired protected KafkaTemplate<String, Object> kafkaTemplate;

  @Autowired protected JdbcTemplate jdbc;

  @DynamicPropertySource
  static void containers(DynamicPropertyRegistry registry) {
    RoutingContainers.register(registry);
  }

  /**
   * Publishes an instruction the way intake-service does: keyed by debtor account, with the five
   * standard headers and a JSON type header.
   *
   * @param instruction the instruction
   * @return the published record, for header comparisons
   */
  protected ProducerRecord<String, Object> publish(PaymentInstruction instruction) {
    return publish(null, instruction);
  }

  /**
   * Publishes an instruction to a chosen partition of {@code payments.instructions}.
   *
   * @param partition partition, or {@code null} to partition by key
   * @param instruction the instruction
   * @return the published record
   */
  protected ProducerRecord<String, Object> publish(
      Integer partition, PaymentInstruction instruction) {
    ProducerRecord<String, Object> record =
        new ProducerRecord<>(
            Topics.INSTRUCTIONS,
            partition,
            instruction.debtor().account(),
            instruction,
            EventHeaders.build(
                EventType.PAYMENT_INSTRUCTION, instruction.batchId().toString(), Instant.now()));
    return send(record);
  }

  /**
   * Sends a record and waits for the acknowledgement.
   *
   * @param record the record
   * @return the same record
   */
  protected ProducerRecord<String, Object> send(ProducerRecord<String, Object> record) {
    try {
      kafkaTemplate.send(record).get(30, TimeUnit.SECONDS);
      return record;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    } catch (ExecutionException | TimeoutException e) {
      throw new IllegalStateException("Could not publish test record", e);
    }
  }

  /**
   * Status events published for one instruction, in publish order.
   *
   * @param idempotencyKey instruction key
   * @return the events
   */
  protected static List<InstructionStatusEvent> statusEvents(String idempotencyKey) {
    return TopicReader.byKey(Topics.STATUS_EVENTS, idempotencyKey).stream()
        .map(r -> parse(r.value(), InstructionStatusEvent.class))
        .toList();
  }

  /**
   * Waits until an instruction has at least {@code count} status events and returns their statuses
   * in publish order.
   *
   * @param idempotencyKey instruction key
   * @param count number of events to wait for
   * @return the statuses
   */
  protected static List<InstructionStatus> awaitStatuses(String idempotencyKey, int count) {
    return await()
        .atMost(WAIT)
        .pollInterval(Duration.ofMillis(250))
        .until(
            () ->
                statusEvents(idempotencyKey).stream().map(InstructionStatusEvent::status).toList(),
            statuses -> statuses.size() >= count);
  }

  /**
   * Waits for the dead-letter record of a batch.
   *
   * @param correlationId batch id carried in the original headers
   * @return the DLT record
   */
  protected static ConsumerRecord<String, String> awaitDeadLetter(String correlationId) {
    return await()
        .atMost(WAIT)
        .pollInterval(Duration.ofMillis(250))
        .until(
            () -> TopicReader.byCorrelationId(Topics.INSTRUCTIONS_DLT, correlationId),
            records -> !records.isEmpty())
        .get(0);
  }

  /**
   * Reads the ledger row of an instruction.
   *
   * @param idempotencyKey instruction key
   * @return column name to value
   */
  protected Map<String, Object> ledgerRow(String idempotencyKey) {
    return jdbc.queryForMap(
        "SELECT status, attempts, channel, amount FROM processed_instruction"
            + " WHERE idempotency_key = ?",
        idempotencyKey);
  }

  /**
   * Parses JSON with the shared Remit settings.
   *
   * @param json JSON text
   * @param type target type
   * @param <T> target type
   * @return the value
   */
  protected static <T> T parse(String json, Class<T> type) {
    try {
      return JSON.readValue(json, type);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Not a " + type.getSimpleName() + ": " + json, e);
    }
  }
}

package ca.musakaleem.remit.api.support;

import static org.awaitility.Awaitility.await;

import ca.musakaleem.remit.api.audit.AuditEvent;
import ca.musakaleem.remit.api.security.JwtService;
import ca.musakaleem.remit.common.Topics;
import ca.musakaleem.remit.common.event.EventHeaders;
import ca.musakaleem.remit.common.event.EventType;
import ca.musakaleem.remit.common.event.PaymentInstruction;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Base class for api-service integration tests: the full application (MockMvc, real security, real
 * consumers) against the shared MySQL, Kafka and MongoDB containers, with the two demo users
 * seeded. Every test uses fresh batch ids and keys, so tests never see each other's rows. All
 * subclasses share one Spring context, so only one set of consumers joins the {@code api-service}
 * group.
 */
@SpringBootTest(
    properties = {
      "remit.security.jwt.secret=" + AbstractApiIT.JWT_SECRET,
      "remit.security.seed-users.enabled=true",
      "remit.security.seed-users.operator-password=" + AbstractApiIT.OPERATOR_PASSWORD,
      "remit.security.seed-users.admin-password=" + AbstractApiIT.ADMIN_PASSWORD
    })
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class AbstractApiIT {

  /** Test-only signing secret (48 bytes); never used outside tests. */
  public static final String JWT_SECRET = "integration-test-secret-0123456789-abcdefghijklm";

  /** Password of the seeded operator in tests. */
  public static final String OPERATOR_PASSWORD = "operator-test-password";

  /** Password of the seeded admin in tests. */
  public static final String ADMIN_PASSWORD = "admin-test-password";

  /** Upper bound for asynchronous consumer outcomes. */
  protected static final Duration WAIT = Duration.ofSeconds(30);

  @Autowired protected MockMvc mvc;

  @Autowired protected JwtService jwtService;

  @Autowired protected JdbcTemplate jdbc;

  @Autowired protected MongoTemplate mongo;

  @Autowired protected KafkaTemplate<String, Object> kafkaTemplate;

  @DynamicPropertySource
  static void containers(DynamicPropertyRegistry registry) {
    ApiContainers.register(registry);
  }

  /**
   * An Authorization header value for the seeded operator.
   *
   * @return {@code Bearer <token>}
   */
  protected String operator() {
    return bearer("operator", "OPERATOR");
  }

  /**
   * An Authorization header value for the seeded admin.
   *
   * @return {@code Bearer <token>}
   */
  protected String admin() {
    return bearer("admin", "OPERATOR", "ADMIN");
  }

  /**
   * An Authorization header value for any user and roles.
   *
   * @param username subject
   * @param roles roles
   * @return {@code Bearer <token>}
   */
  protected String bearer(String username, String... roles) {
    return "Bearer " + jwtService.issue(username, List.of(roles)).token();
  }

  /**
   * Publishes an event the way the Remit producers do: JSON with a type header and the five
   * standard headers.
   *
   * @param topic topic
   * @param key record key
   * @param value event
   * @param type event type header
   * @param correlationId batch id
   * @param occurredAt occurredAt header
   * @param extraHeaders further headers, may be empty
   */
  protected void publish(
      String topic,
      String key,
      Object value,
      EventType type,
      String correlationId,
      Instant occurredAt,
      Header... extraHeaders) {
    ProducerRecord<String, Object> record =
        EventHeaders.record(topic, key, value, type, correlationId, occurredAt);
    for (Header header : extraHeaders) {
      record.headers().add(header);
    }
    sendAndWait(() -> kafkaTemplate.send(record));
  }

  /**
   * Publishes a DLT record the way routing-service's DeadLetterPublishingRecoverer does.
   *
   * @param value the raw value: instruction JSON or poison bytes
   * @param typed whether to add the {@code __TypeId__} header of PaymentInstruction
   * @param correlationId batch id header
   * @param originalPartition source partition
   * @param originalOffset source offset
   * @param causeClass root cause class
   * @param message exception message
   */
  protected void publishDeadLetter(
      byte[] value,
      boolean typed,
      String correlationId,
      int originalPartition,
      long originalOffset,
      String causeClass,
      String message) {
    List<Header> headers = new ArrayList<>();
    if (correlationId != null) {
      headers.addAll(
          EventHeaders.build(EventType.PAYMENT_INSTRUCTION, correlationId, Instant.now()));
    }
    if (typed) {
      headers.add(kafkaHeader("__TypeId__", PaymentInstruction.class.getName()));
    }
    headers.add(kafkaHeader(KafkaHeaders.DLT_ORIGINAL_TOPIC, Topics.INSTRUCTIONS));
    headers.add(
        new RecordHeader(
            KafkaHeaders.DLT_ORIGINAL_PARTITION,
            ByteBuffer.allocate(Integer.BYTES).putInt(originalPartition).array()));
    headers.add(
        new RecordHeader(
            KafkaHeaders.DLT_ORIGINAL_OFFSET,
            ByteBuffer.allocate(Long.BYTES).putLong(originalOffset).array()));
    headers.add(
        kafkaHeader(
            KafkaHeaders.DLT_EXCEPTION_FQCN,
            "org.springframework.kafka.listener.ListenerExecutionFailedException"));
    headers.add(kafkaHeader(KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN, causeClass));
    headers.add(kafkaHeader(KafkaHeaders.DLT_EXCEPTION_MESSAGE, message));
    headers.add(kafkaHeader(KafkaHeaders.DLT_EXCEPTION_STACKTRACE, "at somewhere"));
    headers.add(kafkaHeader(KafkaHeaders.DLT_ORIGINAL_CONSUMER_GROUP, "routing-service"));
    try (KafkaProducer<String, byte[]> producer =
        new KafkaProducer<>(
            Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, ApiContainers.KAFKA.getBootstrapServers()),
            new StringSerializer(),
            new ByteArraySerializer())) {
      producer
          .send(
              new ProducerRecord<>(
                  Topics.INSTRUCTIONS_DLT,
                  originalPartition,
                  TestEvents.DEBTOR_ACCOUNT,
                  value,
                  headers))
          .get(30, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    } catch (ExecutionException | TimeoutException e) {
      throw new IllegalStateException("Could not publish the DLT record", e);
    }
  }

  /**
   * The audit documents about an entity, oldest first.
   *
   * @param entityId entity id
   * @return the documents
   */
  protected List<AuditEvent> auditEvents(String entityId) {
    return mongo.find(
        Query.query(Criteria.where("entityId").is(entityId)).with(Sort.by("occurredAt")),
        AuditEvent.class,
        AuditEvent.COLLECTION);
  }

  /**
   * Waits until a supplier returns a value that is not {@code null}.
   *
   * @param supplier the check
   * @param <T> result type
   * @return the value
   */
  protected static <T> T eventually(Supplier<T> supplier) {
    return await()
        .atMost(WAIT)
        .pollInterval(Duration.ofMillis(200))
        .ignoreExceptions()
        .until(supplier::get, value -> value != null);
  }

  /**
   * A UTF-8 header.
   *
   * @param name name
   * @param value value
   * @return the header
   */
  protected static Header kafkaHeader(String name, String value) {
    return new RecordHeader(name, value.getBytes(StandardCharsets.UTF_8));
  }

  private static void sendAndWait(Supplier<CompletableFuture<?>> send) {
    try {
      send.get().get(30, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    } catch (ExecutionException | TimeoutException e) {
      throw new IllegalStateException("Could not publish test record", e);
    }
  }
}

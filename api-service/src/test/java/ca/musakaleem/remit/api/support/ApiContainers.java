package ca.musakaleem.remit.api.support;

import ca.musakaleem.remit.common.Topics;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.bson.Document;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;

/**
 * MySQL 8.4, Kafka 3.9 (KRaft) and MongoDB 7, started once per test JVM and shared by every
 * integration test (the singleton container pattern). Ryuk removes them when the JVM exits.
 *
 * <p>In compose, kafka-init creates the topics and docker/mongo/init.js creates the audit
 * collection with its indexes (the application user may not). Here the same is done once at
 * startup, so the unique {@code eventId} index exists in tests too.
 */
public final class ApiContainers {

  /** Partitions per topic, as in compose (spec section 5). */
  public static final int PARTITIONS = 3;

  /** The audit database, as in compose. */
  public static final String AUDIT_DATABASE = "remit_audit";

  public static final MySQLContainer<?> MYSQL =
      new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
          .withDatabaseName("remit_api")
          .withUsername("remit_api")
          .withPassword("test-only");

  public static final KafkaContainer KAFKA =
      new KafkaContainer(DockerImageName.parse("apache/kafka:3.9.2"));

  public static final MongoDBContainer MONGO =
      new MongoDBContainer(DockerImageName.parse("mongo:7.0"));

  static {
    Startables.deepStart(MYSQL, KAFKA, MONGO).join();
    createTopics();
    createAuditIndexes();
  }

  private ApiContainers() {}

  /**
   * Points the application at the containers.
   *
   * @param registry dynamic property registry of the test context
   */
  public static void register(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
    registry.add("spring.datasource.username", MYSQL::getUsername);
    registry.add("spring.datasource.password", MYSQL::getPassword);
    registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    registry.add("spring.data.mongodb.uri", () -> MONGO.getReplicaSetUrl(AUDIT_DATABASE));
  }

  /**
   * Admin client settings for the Kafka container.
   *
   * @return client properties
   */
  public static Map<String, Object> adminConfig() {
    return Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
  }

  private static void createTopics() {
    try (Admin admin = Admin.create(adminConfig())) {
      admin
          .createTopics(
              Topics.all().stream().map(t -> new NewTopic(t, PARTITIONS, (short) 1)).toList())
          .all()
          .get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    } catch (ExecutionException e) {
      throw new IllegalStateException("Could not create the Remit topics", e);
    }
  }

  /** The same collection and indexes as docker/mongo/init.js. */
  private static void createAuditIndexes() {
    try (MongoClient client = MongoClients.create(MONGO.getReplicaSetUrl(AUDIT_DATABASE))) {
      MongoCollection<Document> events =
          client.getDatabase(AUDIT_DATABASE).getCollection("audit_events");
      events.createIndex(
          Indexes.ascending("eventId"), new IndexOptions().name("ux_audit_event_id").unique(true));
      events.createIndex(
          Indexes.compoundIndex(Indexes.ascending("entityId"), Indexes.descending("occurredAt")),
          new IndexOptions().name("ix_audit_entity_occurred"));
      events.createIndex(
          Indexes.compoundIndex(Indexes.ascending("eventType"), Indexes.descending("occurredAt")),
          new IndexOptions().name("ix_audit_type_occurred"));
    }
  }
}

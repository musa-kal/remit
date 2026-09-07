package ca.musakaleem.remit.routing.support;

import ca.musakaleem.remit.common.Topics;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;

/**
 * MySQL 8.4 and Kafka 3.9 (KRaft), started once per test JVM and shared by every integration test
 * (the singleton container pattern). Ryuk removes them when the JVM exits.
 */
public final class RoutingContainers {

  /** Partitions of {@code payments.instructions}, as in compose (spec section 5). */
  public static final int PARTITIONS = 3;

  public static final MySQLContainer<?> MYSQL =
      new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
          .withDatabaseName("remit_routing")
          .withUsername("remit_routing")
          .withPassword("test-only");

  public static final KafkaContainer KAFKA =
      new KafkaContainer(DockerImageName.parse("apache/kafka:3.9.2"));

  static {
    Startables.deepStart(MYSQL, KAFKA).join();
    createInstructionsTopic();
  }

  private RoutingContainers() {}

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
  }

  /**
   * Admin client settings for the Kafka container.
   *
   * @return client properties
   */
  public static Map<String, Object> adminConfig() {
    return Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
  }

  /** intake-service owns payments.instructions; in compose kafka-init creates it. */
  private static void createInstructionsTopic() {
    try (Admin admin = Admin.create(adminConfig())) {
      admin
          .createTopics(List.of(new NewTopic(Topics.INSTRUCTIONS, PARTITIONS, (short) 1)))
          .all()
          .get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    } catch (ExecutionException e) {
      throw new IllegalStateException("Could not create " + Topics.INSTRUCTIONS, e);
    }
  }
}

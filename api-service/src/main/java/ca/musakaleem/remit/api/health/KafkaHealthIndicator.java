package ca.musakaleem.remit.api.health;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.DescribeClusterOptions;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.apache.kafka.common.KafkaException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;

/**
 * Reports the Kafka broker in {@code /actuator/health} as component {@code kafka}, next to Boot's
 * {@code db} and {@code mongo}. Spring Boot 3 has no built-in Kafka indicator, so this asks the
 * cluster for its id and nodes with a bounded timeout: UP when it answers, DOWN otherwise.
 */
@Component
public class KafkaHealthIndicator implements HealthIndicator {

  private final Supplier<Admin> adminFactory;
  private final Duration timeout;

  /**
   * Creates the indicator with admin clients built from Boot's Kafka settings.
   *
   * @param kafkaAdmin Spring Kafka admin holding the bootstrap servers
   * @param timeout how long one check may take
   */
  @Autowired
  public KafkaHealthIndicator(
      KafkaAdmin kafkaAdmin, @Value("${remit.kafka.health-timeout:5s}") Duration timeout) {
    this(() -> Admin.create(kafkaAdmin.getConfigurationProperties()), timeout);
  }

  /**
   * Creates the indicator with a custom admin client source.
   *
   * @param adminFactory creates a short-lived admin client per check
   * @param timeout how long one check may take
   */
  KafkaHealthIndicator(Supplier<Admin> adminFactory, Duration timeout) {
    this.adminFactory = adminFactory;
    this.timeout = timeout;
  }

  @Override
  public Health health() {
    long timeoutMs = timeout.toMillis();
    Admin admin = adminFactory.get();
    try {
      DescribeClusterResult cluster =
          admin.describeCluster(new DescribeClusterOptions().timeoutMs((int) timeoutMs));
      String clusterId = cluster.clusterId().get(timeoutMs, TimeUnit.MILLISECONDS);
      int nodes = cluster.nodes().get(timeoutMs, TimeUnit.MILLISECONDS).size();
      return Health.up().withDetail("clusterId", clusterId).withDetail("nodes", nodes).build();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Health.down(e).build();
    } catch (ExecutionException | TimeoutException | KafkaException e) {
      return Health.down(e).build();
    } finally {
      admin.close(timeout);
    }
  }
}

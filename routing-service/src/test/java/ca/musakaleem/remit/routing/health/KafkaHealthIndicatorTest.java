package ca.musakaleem.remit.routing.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.DescribeClusterOptions;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.internals.KafkaFutureImpl;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.kafka.core.KafkaAdmin;

class KafkaHealthIndicatorTest {

  private static final Duration TIMEOUT = Duration.ofMillis(200);

  private final Admin admin = mock(Admin.class);
  private final DescribeClusterResult cluster = mock(DescribeClusterResult.class);
  private final KafkaHealthIndicator indicator = new KafkaHealthIndicator(() -> admin, TIMEOUT);

  @Test
  void upWithClusterIdAndNodeCountWhenTheClusterAnswers() {
    when(admin.describeCluster(any(DescribeClusterOptions.class))).thenReturn(cluster);
    when(cluster.clusterId()).thenReturn(KafkaFuture.completedFuture("cluster-1"));
    when(cluster.nodes())
        .thenReturn(KafkaFuture.completedFuture(List.of(new Node(1, "kafka", 9092))));

    Health health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.UP);
    assertThat(health.getDetails())
        .containsEntry("clusterId", "cluster-1")
        .containsEntry("nodes", 1);
    verify(admin).close(TIMEOUT);
  }

  @Test
  void asksTheClusterWithTheConfiguredTimeout() {
    when(admin.describeCluster(any(DescribeClusterOptions.class))).thenReturn(cluster);
    when(cluster.clusterId()).thenReturn(KafkaFuture.completedFuture("cluster-1"));
    when(cluster.nodes()).thenReturn(KafkaFuture.completedFuture(List.of()));
    ArgumentCaptor<DescribeClusterOptions> options =
        ArgumentCaptor.forClass(DescribeClusterOptions.class);

    indicator.health();

    verify(admin).describeCluster(options.capture());
    assertThat(options.getValue().timeoutMs()).isEqualTo(200);
  }

  @Test
  void downWhenTheClusterCallFails() {
    KafkaFutureImpl<String> failed = new KafkaFutureImpl<>();
    failed.completeExceptionally(new TimeoutException("Timed out waiting for a node assignment"));
    when(admin.describeCluster(any(DescribeClusterOptions.class))).thenReturn(cluster);
    when(cluster.clusterId()).thenReturn(failed);

    Health health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    assertThat(health.getDetails().get("error").toString()).contains("node assignment");
    verify(admin).close(TIMEOUT);
  }

  @Test
  void downWhenTheClusterDoesNotAnswerInTime() {
    when(admin.describeCluster(any(DescribeClusterOptions.class))).thenReturn(cluster);
    when(cluster.clusterId()).thenReturn(new KafkaFutureImpl<>());

    assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
  }

  @Test
  void downAndStillInterruptedWhenInterrupted() {
    when(admin.describeCluster(any(DescribeClusterOptions.class))).thenReturn(cluster);
    when(cluster.clusterId()).thenReturn(new KafkaFutureImpl<>());

    Thread.currentThread().interrupt();
    try {
      assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void buildsAdminClientsFromBootsKafkaSettings() {
    KafkaAdmin kafkaAdmin = new KafkaAdmin(Map.of("bootstrap.servers", "localhost:1"));
    KafkaHealthIndicator real = new KafkaHealthIndicator(kafkaAdmin, Duration.ofMillis(300));

    assertThat(real.health().getStatus()).isEqualTo(Status.DOWN);
  }
}

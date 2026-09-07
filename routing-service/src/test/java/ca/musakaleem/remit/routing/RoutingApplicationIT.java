package ca.musakaleem.remit.routing;

import static org.assertj.core.api.Assertions.assertThat;

import ca.musakaleem.remit.common.Topics;
import ca.musakaleem.remit.routing.consumer.InstructionListener;
import ca.musakaleem.remit.routing.support.AbstractRoutingIT;
import ca.musakaleem.remit.routing.support.RoutingContainers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Set;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties.AckMode;

class RoutingApplicationIT extends AbstractRoutingIT {

  @Autowired ApplicationContext context;

  @Autowired TestRestTemplate http;

  @Autowired ProducerFactory<?, ?> producerFactory;

  @Autowired ConsumerFactory<?, ?> consumerFactory;

  @Autowired KafkaListenerEndpointRegistry listeners;

  @Test
  void startsWithTheTestProfile() {
    assertThat(context.getEnvironment().getActiveProfiles()).containsExactly("test");
    assertThat(context.getEnvironment().getProperty("spring.application.name"))
        .isEqualTo("routing-service");
  }

  @Test
  void healthIncludesKafkaAndDatabaseAndBothAreUp() throws Exception {
    ResponseEntity<String> response = http.getForEntity("/actuator/health", String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    JsonNode health = new ObjectMapper().readTree(response.getBody());
    assertThat(health.path("status").asText()).isEqualTo("UP");
    assertThat(health.path("components").path("db").path("status").asText()).isEqualTo("UP");
    assertThat(health.path("components").path("kafka").path("status").asText()).isEqualTo("UP");
    assertThat(health.path("components").path("kafka").path("details").path("nodes").asInt())
        .isEqualTo(1);
  }

  @Test
  void exposesLivenessAndReadinessProbes() {
    assertThat(http.getForEntity("/actuator/health/liveness", String.class).getBody())
        .contains("\"status\":\"UP\"");
    assertThat(http.getForEntity("/actuator/health/readiness", String.class).getBody())
        .contains("\"status\":\"UP\"");
  }

  @Test
  void producerIsIdempotentWithAcksAll() {
    Map<String, Object> config = producerFactory.getConfigurationProperties();

    assertThat(config).containsEntry(ProducerConfig.ACKS_CONFIG, "all");
    assertThat(config).containsEntry(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
  }

  @Test
  void consumerCommitsManuallyPerRecordWithThreeThreadsInTheRoutingGroup() {
    ConcurrentMessageListenerContainer<?, ?> container =
        (ConcurrentMessageListenerContainer<?, ?>)
            listeners.getListenerContainer(InstructionListener.LISTENER_ID);

    assertThat(container).isNotNull();
    assertThat(container.getGroupId()).isEqualTo("routing-service");
    assertThat(container.getConcurrency()).isEqualTo(3);
    assertThat(container.getContainerProperties().getAckMode()).isEqualTo(AckMode.RECORD);
    assertThat(container.getContainerProperties().getTopics()).containsExactly(Topics.INSTRUCTIONS);
    assertThat(consumerFactory.getConfigurationProperties())
        .containsEntry(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
  }

  @Test
  void createsTheRoutedStatusAndDeadLetterTopicsWithThreePartitions() throws Exception {
    try (Admin admin = Admin.create(RoutingContainers.adminConfig())) {
      Map<String, TopicDescription> topics =
          admin
              .describeTopics(
                  Set.of(
                      Topics.ROUTED_EFT,
                      Topics.ROUTED_WIRE,
                      Topics.ROUTED_INTERAC,
                      Topics.STATUS_EVENTS,
                      Topics.INSTRUCTIONS_DLT))
              .allTopicNames()
              .get();

      assertThat(topics).hasSize(5);
      assertThat(topics.values()).allSatisfy(t -> assertThat(t.partitions()).hasSize(3));
    }
  }
}

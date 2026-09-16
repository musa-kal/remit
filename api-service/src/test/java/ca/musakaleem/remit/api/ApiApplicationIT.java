package ca.musakaleem.remit.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.musakaleem.remit.api.batch.BatchEventListener;
import ca.musakaleem.remit.api.deadletter.DeadLetterListener;
import ca.musakaleem.remit.api.instruction.RoutedInstructionListener;
import ca.musakaleem.remit.api.instruction.StatusEventListener;
import ca.musakaleem.remit.api.rejection.RejectionListener;
import ca.musakaleem.remit.api.support.AbstractApiIT;
import ca.musakaleem.remit.common.Topics;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties.AckMode;
import org.springframework.kafka.listener.MessageListenerContainer;

class ApiApplicationIT extends AbstractApiIT {

  @Autowired ApplicationContext context;

  @Autowired ProducerFactory<?, ?> producerFactory;

  @Autowired ConsumerFactory<?, ?> consumerFactory;

  @Autowired KafkaListenerEndpointRegistry listeners;

  @Test
  void startsWithTheTestProfile() {
    assertThat(context.getEnvironment().getActiveProfiles()).containsExactly("test");
    assertThat(context.getEnvironment().getProperty("spring.application.name"))
        .isEqualTo("api-service");
  }

  @Test
  void healthIsUpWithDatabaseMongoAndKafkaWithoutAToken() throws Exception {
    mvc.perform(get("/actuator/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"))
        .andExpect(jsonPath("$.components.db.status").value("UP"))
        .andExpect(jsonPath("$.components.mongo.status").value("UP"))
        .andExpect(jsonPath("$.components.kafka.status").value("UP"))
        .andExpect(jsonPath("$.components.kafka.details.nodes").value(1));
  }

  @Test
  void exposesLivenessAndReadinessProbes() throws Exception {
    mvc.perform(get("/actuator/health/liveness"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"));
    mvc.perform(get("/actuator/health/readiness"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"));
  }

  @Test
  void metricsNeedAToken() throws Exception {
    mvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
    mvc.perform(get("/actuator/metrics").header("Authorization", operator()))
        .andExpect(status().isOk());
  }

  @Test
  void echoesOrGeneratesACorrelationId() throws Exception {
    mvc.perform(get("/actuator/health").header("X-Correlation-Id", "abc-123"))
        .andExpect(header().string("X-Correlation-Id", "abc-123"));
    mvc.perform(get("/actuator/health").header("X-Correlation-Id", "bad value with spaces"))
        .andExpect(header().string("X-Correlation-Id", org.hamcrest.Matchers.hasLength(36)));
  }

  @Test
  void producerIsIdempotentWithAcksAll() {
    Map<String, Object> config = producerFactory.getConfigurationProperties();

    assertThat(config).containsEntry(ProducerConfig.ACKS_CONFIG, "all");
    assertThat(config).containsEntry(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
  }

  @Test
  void everyListenerIsInTheApiServiceGroupWithRecordAcks() {
    assertThat(consumerFactory.getConfigurationProperties())
        .containsEntry(ConsumerConfig.GROUP_ID_CONFIG, "api-service")
        .containsEntry(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
    Map<String, String> topics =
        Map.of(
            BatchEventListener.LISTENER_ID, Topics.BATCH_EVENTS,
            StatusEventListener.LISTENER_ID, Topics.STATUS_EVENTS,
            RejectionListener.LISTENER_ID, Topics.REJECTED,
            DeadLetterListener.LISTENER_ID, Topics.INSTRUCTIONS_DLT,
            RoutedInstructionListener.LISTENER_ID, Topics.ROUTED_EFT);
    topics.forEach(
        (id, topic) -> {
          MessageListenerContainer container = listeners.getListenerContainer(id);
          assertThat(container).as(id).isNotNull();
          assertThat(container.getGroupId()).isEqualTo("api-service");
          assertThat(container.getContainerProperties().getAckMode()).isEqualTo(AckMode.RECORD);
          assertThat(container.getContainerProperties().getTopics()).contains(topic);
          assertThat(container.isRunning()).isTrue();
        });
  }

  @Test
  void servesSwaggerUiAndTheOpenApiDocumentWithBearerAuth() throws Exception {
    mvc.perform(get("/swagger-ui.html")).andExpect(status().is3xxRedirection());
    mvc.perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
        .andExpect(jsonPath("$.paths['/api/v1/dead-letters/{id}/replay'].post").exists());
  }
}

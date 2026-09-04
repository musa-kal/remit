package ca.musakaleem.remit.intake;

import static org.assertj.core.api.Assertions.assertThat;

import ca.musakaleem.remit.common.Topics;
import ca.musakaleem.remit.common.event.Channel;
import ca.musakaleem.remit.intake.config.RoutingRulesProperties;
import ca.musakaleem.remit.intake.support.AbstractIntakeIT;
import ca.musakaleem.remit.intake.support.IntakeContainers;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.ProducerFactory;

class IntakeApplicationIT extends AbstractIntakeIT {

  @Autowired ApplicationContext context;

  @Autowired TestRestTemplate http;

  @Autowired ProducerFactory<?, ?> producerFactory;

  @Autowired RoutingRulesProperties routingRules;

  @Test
  void startsWithTheTestProfile() {
    assertThat(context.getEnvironment().getActiveProfiles()).containsExactly("test");
    assertThat(context.getEnvironment().getProperty("spring.application.name"))
        .isEqualTo("intake-service");
  }

  @Test
  void exposesLivenessAndReadinessProbes() {
    assertThat(get("/actuator/health/liveness").getBody()).contains("\"status\":\"UP\"");
    assertThat(get("/actuator/health/readiness").getBody()).contains("\"status\":\"UP\"");
    assertThat(get("/actuator/health").getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  void exposesInfoMetricsAndTheIngestJobsEndpoint() {
    assertThat(get("/actuator/info").getBody()).contains("intake-service");
    assertThat(get("/actuator/metrics").getBody()).contains("jvm.memory.used");
    assertThat(get("/actuator/ingestjobs").getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  void producerIsIdempotentWithAcksAll() {
    Map<String, Object> config = producerFactory.getConfigurationProperties();

    assertThat(config).containsEntry(ProducerConfig.ACKS_CONFIG, "all");
    assertThat(config).containsEntry(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
  }

  @Test
  void createsTheIntakeTopicsWithThreePartitions() throws Exception {
    try (AdminClient admin =
        AdminClient.create(
            Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                IntakeContainers.KAFKA.getBootstrapServers()))) {
      Map<String, TopicDescription> topics =
          admin
              .describeTopics(Set.of(Topics.INSTRUCTIONS, Topics.REJECTED, Topics.BATCH_EVENTS))
              .allTopicNames()
              .get();

      assertThat(topics.values()).allSatisfy(t -> assertThat(t.partitions()).hasSize(3));
    }
  }

  @Test
  void bindsTheIllustrativeChannelRules() {
    assertThat(routingRules.ruleFor(Channel.INTERAC).maxAmount())
        .isEqualByComparingTo(new BigDecimal("10000.00"));
    assertThat(routingRules.ruleFor(Channel.INTERAC).currencies()).containsExactly("CAD");
    assertThat(routingRules.ruleFor(Channel.EFT).maxAmount())
        .isEqualByComparingTo(new BigDecimal("25000000.00"));
    assertThat(routingRules.ruleFor(Channel.WIRE).maxAmount()).isNull();
  }

  private ResponseEntity<String> get(String path) {
    return http.getForEntity(path, String.class);
  }
}

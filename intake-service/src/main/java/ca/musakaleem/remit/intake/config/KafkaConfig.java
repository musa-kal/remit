package ca.musakaleem.remit.intake.config;

import ca.musakaleem.remit.common.RemitJson;
import ca.musakaleem.remit.common.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.DefaultKafkaProducerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;

/**
 * Kafka topics intake-service produces to and the JSON value serializer. Producer reliability
 * settings (acks=all, idempotence) live in application.yml.
 */
@Configuration(proxyBeanMethods = false)
public class KafkaConfig {

  private final int partitions;
  private final int replicas;

  /**
   * Creates the configuration.
   *
   * @param partitions partitions per topic (3 locally, spec section 5)
   * @param replicas replication factor
   */
  public KafkaConfig(
      @Value("${remit.kafka.partitions:3}") int partitions,
      @Value("${remit.kafka.replication-factor:1}") int replicas) {
    this.partitions = partitions;
    this.replicas = replicas;
  }

  /**
   * Topic for valid instructions.
   *
   * @return topic definition
   */
  @Bean
  public NewTopic instructionsTopic() {
    return topic(Topics.INSTRUCTIONS);
  }

  /**
   * Topic for rejected rows.
   *
   * @return topic definition
   */
  @Bean
  public NewTopic rejectedTopic() {
    return topic(Topics.REJECTED);
  }

  /**
   * Topic for batch lifecycle events.
   *
   * @return topic definition
   */
  @Bean
  public NewTopic batchEventsTopic() {
    return topic(Topics.BATCH_EVENTS);
  }

  /**
   * Uses the shared Remit Jackson settings (ISO dates, plain decimals) for event JSON.
   *
   * @return customizer applied to Boot's producer factory
   */
  @Bean
  public DefaultKafkaProducerFactoryCustomizer remitJsonValueSerializer() {
    return factory -> useRemitJson(factory);
  }

  @SuppressWarnings("unchecked")
  private static void useRemitJson(DefaultKafkaProducerFactory<?, ?> factory) {
    ((DefaultKafkaProducerFactory<Object, Object>) factory)
        .setValueSerializer(new JsonSerializer<>(RemitJson.objectMapper()));
  }

  private NewTopic topic(String name) {
    return TopicBuilder.name(name).partitions(partitions).replicas(replicas).build();
  }
}

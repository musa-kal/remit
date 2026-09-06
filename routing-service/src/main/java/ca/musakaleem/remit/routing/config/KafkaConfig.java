package ca.musakaleem.remit.routing.config;

import ca.musakaleem.remit.common.KafkaJson;
import ca.musakaleem.remit.common.RemitJson;
import ca.musakaleem.remit.common.Topics;
import ca.musakaleem.remit.common.event.PaymentInstruction;
import java.util.Map;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.DefaultKafkaConsumerFactoryCustomizer;
import org.springframework.boot.autoconfigure.kafka.DefaultKafkaProducerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;

/**
 * Kafka topics routing-service produces to and the JSON serializers (spec section 5). Consumer and
 * producer reliability settings (group, ack mode, acks=all, idempotence) live in application.yml.
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
   * Topic for EFT instructions.
   *
   * @return topic definition
   */
  @Bean
  public NewTopic routedEftTopic() {
    return topic(Topics.ROUTED_EFT);
  }

  /**
   * Topic for WIRE instructions.
   *
   * @return topic definition
   */
  @Bean
  public NewTopic routedWireTopic() {
    return topic(Topics.ROUTED_WIRE);
  }

  /**
   * Topic for INTERAC instructions.
   *
   * @return topic definition
   */
  @Bean
  public NewTopic routedInteracTopic() {
    return topic(Topics.ROUTED_INTERAC);
  }

  /**
   * Topic for instruction status events.
   *
   * @return topic definition
   */
  @Bean
  public NewTopic statusEventsTopic() {
    return topic(Topics.STATUS_EVENTS);
  }

  /**
   * Dead-letter topic for instructions that failed after retries.
   *
   * @return topic definition
   */
  @Bean
  public NewTopic deadLetterTopic() {
    return topic(Topics.INSTRUCTIONS_DLT);
  }

  /**
   * Uses the shared Remit Jackson settings for event JSON, and raw bytes for the value of a poison
   * pill that is forwarded to the dead-letter topic unchanged.
   *
   * @return customizer applied to Boot's producer factory
   */
  @Bean
  public DefaultKafkaProducerFactoryCustomizer remitValueSerializer() {
    return KafkaConfig::useRemitSerializer;
  }

  /**
   * Reads instruction values as JSON with the shared Remit Jackson settings, wrapped in {@link
   * ErrorHandlingDeserializer} so a record that cannot be read goes to the dead-letter topic
   * instead of blocking its partition.
   *
   * @return customizer applied to Boot's consumer factory
   */
  @Bean
  public DefaultKafkaConsumerFactoryCustomizer remitInstructionDeserializer() {
    return KafkaConfig::useRemitDeserializer;
  }

  /**
   * The value serializer: byte arrays as-is, everything else as JSON with a type header.
   *
   * @return the serializer
   */
  static Serializer<Object> valueSerializer() {
    Map<Class<?>, Serializer<?>> delegates =
        Map.of(
            byte[].class,
            new ByteArraySerializer(),
            Object.class,
            new JsonSerializer<>(RemitJson.objectMapper()));
    // assignable = true picks the most specific matching class, so byte[] wins over Object.
    return new DelegatingByTypeSerializer(delegates, true);
  }

  /**
   * The value deserializer: JSON into {@link PaymentInstruction}, trusting type headers only from
   * the Remit event package, wrapped so failures become DLT records rather than exceptions.
   *
   * @return the deserializer
   */
  static Deserializer<PaymentInstruction> instructionDeserializer() {
    JsonDeserializer<PaymentInstruction> json =
        new JsonDeserializer<>(PaymentInstruction.class, RemitJson.objectMapper());
    json.addTrustedPackages(KafkaJson.TRUSTED_PACKAGES);
    return new ErrorHandlingDeserializer<>(json);
  }

  @SuppressWarnings("unchecked")
  private static void useRemitSerializer(DefaultKafkaProducerFactory<?, ?> factory) {
    ((DefaultKafkaProducerFactory<Object, Object>) factory).setValueSerializer(valueSerializer());
  }

  @SuppressWarnings("unchecked")
  private static void useRemitDeserializer(DefaultKafkaConsumerFactory<?, ?> factory) {
    DefaultKafkaConsumerFactory<String, PaymentInstruction> typed =
        (DefaultKafkaConsumerFactory<String, PaymentInstruction>) factory;
    typed.setKeyDeserializer(new StringDeserializer());
    typed.setValueDeserializer(instructionDeserializer());
  }

  private NewTopic topic(String name) {
    return TopicBuilder.name(name).partitions(partitions).replicas(replicas).build();
  }
}

package ca.musakaleem.remit.api.config;

import ca.musakaleem.remit.api.messaging.UnexpectedPayloadException;
import ca.musakaleem.remit.common.KafkaJson;
import ca.musakaleem.remit.common.RemitJson;
import ca.musakaleem.remit.common.Topics;
import java.util.Map;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.boot.autoconfigure.kafka.DefaultKafkaConsumerFactoryCustomizer;
import org.springframework.boot.autoconfigure.kafka.DefaultKafkaProducerFactoryCustomizer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;

/**
 * Kafka wiring of api-service (spec section 5). Event listeners read JSON whose class comes from
 * the {@code __TypeId__} header, trusted only from the Remit event package. The dead-letter
 * listener reads raw bytes, because a poison pill on the DLT is not JSON. Replays are written as
 * JSON with a type header, like intake-service writes instructions.
 */
@Configuration(proxyBeanMethods = false)
public class KafkaConfig {

  /** Container factory of the dead-letter listener: string keys, byte array values. */
  public static final String DLT_CONTAINER_FACTORY = "dltListenerContainerFactory";

  private static final Logger log = LoggerFactory.getLogger(KafkaConfig.class);

  /** Retries after the first failed attempt of a record. */
  static final int MAX_RETRIES = 4;

  /**
   * Declares {@code payments.instructions}, which api-service writes replays to. intake-service
   * declares it too with the same settings; whichever starts first creates it.
   *
   * @param partitions partitions per topic
   * @param replicas replication factor
   * @return topic definition
   */
  @Bean
  public NewTopic instructionsTopic(
      @Value("${remit.kafka.partitions:3}") int partitions,
      @Value("${remit.kafka.replication-factor:1}") int replicas) {
    return TopicBuilder.name(Topics.INSTRUCTIONS).partitions(partitions).replicas(replicas).build();
  }

  /**
   * Writes replayed instructions as JSON with the shared Remit Jackson settings and a type header.
   *
   * @return customizer applied to Boot's producer factory
   */
  @Bean
  public DefaultKafkaProducerFactoryCustomizer remitJsonSerializer() {
    return KafkaConfig::useJsonSerializer;
  }

  /**
   * Reads event values as JSON by type header, wrapped so an unreadable record is skipped instead
   * of blocking its partition.
   *
   * @return customizer applied to Boot's consumer factory
   */
  @Bean
  public DefaultKafkaConsumerFactoryCustomizer remitEventDeserializer() {
    return KafkaConfig::useEventDeserializer;
  }

  /**
   * Retries a failing record with back-off (0.5 s doubling to 5 s, 5 attempts in all), then logs
   * and skips it. Unreadable records and unexpected payloads are skipped at once. Every projection
   * is idempotent, so a retry never applies an event twice.
   *
   * @return the error handler Boot installs in every listener container factory
   */
  @Bean
  public DefaultErrorHandler kafkaErrorHandler() {
    ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(MAX_RETRIES);
    backOff.setInitialInterval(500);
    backOff.setMultiplier(2.0);
    backOff.setMaxInterval(5_000);
    DefaultErrorHandler handler =
        new DefaultErrorHandler(
            (record, exception) ->
                log.error(
                    "Skipping {}-{}@{} after it failed: {}",
                    record.topic(),
                    record.partition(),
                    record.offset(),
                    exception.getMessage()),
            backOff);
    handler.addNotRetryableExceptions(UnexpectedPayloadException.class);
    return handler;
  }

  /**
   * Listener container factory for {@code payments.instructions.DLT}, configured like Boot's
   * default factory (ack mode, error handler) but with a byte array value deserializer.
   *
   * @param configurer Boot's factory configurer
   * @param properties Boot's Kafka properties
   * @return the factory
   */
  @Bean(DLT_CONTAINER_FACTORY)
  public ConcurrentKafkaListenerContainerFactory<String, byte[]> dltListenerContainerFactory(
      ConcurrentKafkaListenerContainerFactoryConfigurer configurer, KafkaProperties properties) {
    Map<String, Object> config = properties.buildConsumerProperties(null);
    ConsumerFactory<String, byte[]> consumerFactory =
        new DefaultKafkaConsumerFactory<>(
            config, new StringDeserializer(), new ByteArrayDeserializer());
    ConcurrentKafkaListenerContainerFactory<String, byte[]> factory =
        new ConcurrentKafkaListenerContainerFactory<>();
    configure(configurer, factory, consumerFactory);
    return factory;
  }

  /**
   * The event value deserializer.
   *
   * @return JSON by type header inside an error handling deserializer
   */
  static Deserializer<Object> eventDeserializer() {
    JsonDeserializer<Object> json = new JsonDeserializer<>(RemitJson.objectMapper());
    json.addTrustedPackages(KafkaJson.TRUSTED_PACKAGES);
    json.setUseTypeHeaders(true);
    return new ErrorHandlingDeserializer<>(json);
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static void configure(
      ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
      ConcurrentKafkaListenerContainerFactory<String, byte[]> factory,
      ConsumerFactory<String, byte[]> consumerFactory) {
    configurer.configure(
        (ConcurrentKafkaListenerContainerFactory) factory, (ConsumerFactory) consumerFactory);
  }

  @SuppressWarnings("unchecked")
  private static void useJsonSerializer(DefaultKafkaProducerFactory<?, ?> factory) {
    ((DefaultKafkaProducerFactory<Object, Object>) factory)
        .setValueSerializer(new JsonSerializer<>(RemitJson.objectMapper()));
  }

  @SuppressWarnings("unchecked")
  private static void useEventDeserializer(DefaultKafkaConsumerFactory<?, ?> factory) {
    DefaultKafkaConsumerFactory<String, Object> typed =
        (DefaultKafkaConsumerFactory<String, Object>) factory;
    typed.setKeyDeserializer(new StringDeserializer());
    typed.setValueDeserializer(eventDeserializer());
  }
}

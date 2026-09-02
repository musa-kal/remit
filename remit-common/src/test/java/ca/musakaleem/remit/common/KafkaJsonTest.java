package ca.musakaleem.remit.common;

import static org.assertj.core.api.Assertions.assertThat;

import ca.musakaleem.remit.common.event.PaymentInstruction;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;

class KafkaJsonTest {

  @Test
  void propertyNamesMatchSpringKafka() {
    assertThat(KafkaJson.TRUSTED_PACKAGES_PROPERTY).isEqualTo(JsonDeserializer.TRUSTED_PACKAGES);
    assertThat(KafkaJson.ADD_TYPE_HEADERS_PROPERTY).isEqualTo(JsonSerializer.ADD_TYPE_INFO_HEADERS);
    assertThat(KafkaJson.USE_TYPE_HEADERS_PROPERTY)
        .isEqualTo(JsonDeserializer.USE_TYPE_INFO_HEADERS);
    assertThat(KafkaJson.VALUE_DEFAULT_TYPE_PROPERTY)
        .isEqualTo(JsonDeserializer.VALUE_DEFAULT_TYPE);
    assertThat(KafkaJson.VALUE_DELEGATE_PROPERTY)
        .isEqualTo(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS);
  }

  @Test
  void classNamesMatchSpringKafka() {
    assertThat(KafkaJson.JSON_SERIALIZER).isEqualTo(JsonSerializer.class.getName());
    assertThat(KafkaJson.JSON_DESERIALIZER).isEqualTo(JsonDeserializer.class.getName());
    assertThat(KafkaJson.ERROR_HANDLING_DESERIALIZER)
        .isEqualTo(ErrorHandlingDeserializer.class.getName());
  }

  @Test
  void trustedPackageIsTheEventPackage() {
    assertThat(KafkaJson.TRUSTED_PACKAGES).isEqualTo(PaymentInstruction.class.getPackageName());
  }

  @Test
  void producerPropertiesUseJsonWithTypeHeaders() {
    assertThat(KafkaJson.producerProperties())
        .containsEntry(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, KafkaJson.JSON_SERIALIZER)
        .containsEntry(JsonSerializer.ADD_TYPE_INFO_HEADERS, true);
  }

  @Test
  void consumerPropertiesWrapJsonInErrorHandlingDeserializer() {
    assertThat(KafkaJson.consumerProperties())
        .containsEntry(
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, KafkaJson.ERROR_HANDLING_DESERIALIZER)
        .containsEntry(
            ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, KafkaJson.JSON_DESERIALIZER)
        .containsEntry(JsonDeserializer.TRUSTED_PACKAGES, "ca.musakaleem.remit.common.event")
        .containsEntry(JsonDeserializer.USE_TYPE_INFO_HEADERS, true);
  }
}

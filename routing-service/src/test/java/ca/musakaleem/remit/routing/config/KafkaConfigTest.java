package ca.musakaleem.remit.routing.config;

import static ca.musakaleem.remit.routing.support.TestInstructions.instruction;
import static org.assertj.core.api.Assertions.assertThat;

import ca.musakaleem.remit.common.event.Channel;
import ca.musakaleem.remit.common.event.EventHeaders;
import ca.musakaleem.remit.common.event.PaymentInstruction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.SerializationUtils;

class KafkaConfigTest {

  private static final String TOPIC = "payments.instructions";

  private final KafkaConfig config = new KafkaConfig(3, 1);

  @Test
  void declaresTheFiveTopicsRoutingProducesToWithThreePartitions() {
    List<NewTopic> topics =
        List.of(
            config.routedEftTopic(),
            config.routedWireTopic(),
            config.routedInteracTopic(),
            config.statusEventsTopic(),
            config.deadLetterTopic());

    assertThat(topics)
        .extracting(NewTopic::name)
        .containsExactly(
            "payments.routed.eft",
            "payments.routed.wire",
            "payments.routed.interac",
            "payments.status-events",
            "payments.instructions.DLT");
    assertThat(topics).allSatisfy(t -> assertThat(t.numPartitions()).isEqualTo(3));
    assertThat(topics).allSatisfy(t -> assertThat(t.replicationFactor()).isEqualTo((short) 1));
  }

  @Test
  void writesEventsAsJsonWithATypeHeaderAndPlainDecimals() {
    PaymentInstruction instruction = instruction(Channel.WIRE, "1500000.00", "USD");
    Headers headers = new RecordHeaders();

    byte[] json = KafkaConfig.valueSerializer().serialize(TOPIC, headers, instruction);

    assertThat(new String(json, StandardCharsets.UTF_8))
        .contains("\"amount\":1500000.00")
        .contains("\"valueDate\":\"2026-10-15\"");
    assertThat(EventHeaders.lastValue(headers, "__TypeId__"))
        .contains(PaymentInstruction.class.getName());
  }

  @Test
  void writesRawBytesUnchangedSoPoisonPillsReachTheDltAsTheyWere() {
    byte[] raw = "{not json".getBytes(StandardCharsets.UTF_8);
    Headers headers = new RecordHeaders();

    assertThat(KafkaConfig.valueSerializer().serialize(TOPIC, headers, raw)).isEqualTo(raw);
    assertThat(headers.lastHeader("__TypeId__")).isNull();
  }

  @Test
  void readsAnInstructionWrittenWithATypeHeader() {
    PaymentInstruction instruction = instruction(Channel.EFT, "1250.00", "CAD");
    Headers headers = new RecordHeaders();
    byte[] json = KafkaConfig.valueSerializer().serialize(TOPIC, headers, instruction);

    PaymentInstruction read =
        KafkaConfig.instructionDeserializer().deserialize(TOPIC, headers, json);

    assertThat(read).isEqualTo(instruction);
  }

  @Test
  void readsAnInstructionWithoutATypeHeader() {
    PaymentInstruction instruction = instruction(Channel.EFT, "1250.00", "CAD");
    byte[] json = KafkaConfig.valueSerializer().serialize(TOPIC, new RecordHeaders(), instruction);

    assertThat(KafkaConfig.instructionDeserializer().deserialize(TOPIC, new RecordHeaders(), json))
        .isEqualTo(instruction);
  }

  @Test
  void malformedJsonBecomesADeserializationExceptionHeaderNotAnException() {
    Headers headers = new RecordHeaders();

    PaymentInstruction read =
        KafkaConfig.instructionDeserializer()
            .deserialize(TOPIC, headers, "{not json".getBytes(StandardCharsets.UTF_8));

    assertThat(read).isNull();
    assertThat(headers.lastHeader(SerializationUtils.VALUE_DESERIALIZER_EXCEPTION_HEADER))
        .isNotNull();
  }

  @Test
  void aTypeHeaderOutsideTheEventPackageIsRejected() {
    Headers headers = new RecordHeaders();
    headers.add("__TypeId__", "com.example.Untrusted".getBytes(StandardCharsets.UTF_8));

    PaymentInstruction read =
        KafkaConfig.instructionDeserializer()
            .deserialize(TOPIC, headers, "{}".getBytes(StandardCharsets.UTF_8));

    assertThat(read).isNull();
    assertThat(headers.lastHeader(SerializationUtils.VALUE_DESERIALIZER_EXCEPTION_HEADER))
        .isNotNull();
  }

  @Test
  void customizersInstallTheSerializers() {
    DefaultKafkaProducerFactory<Object, Object> producers =
        new DefaultKafkaProducerFactory<>(Map.of());
    DefaultKafkaConsumerFactory<Object, Object> consumers =
        new DefaultKafkaConsumerFactory<>(Map.of());

    config.remitValueSerializer().customize(producers);
    config.remitInstructionDeserializer().customize(consumers);

    Serializer<Object> serializer = producers.getValueSerializer();
    Deserializer<Object> keys = consumers.getKeyDeserializer();
    Deserializer<Object> values = consumers.getValueDeserializer();
    assertThat(serializer).isInstanceOf(DelegatingByTypeSerializer.class);
    assertThat(keys).isInstanceOf(StringDeserializer.class);
    assertThat(values).isInstanceOf(ErrorHandlingDeserializer.class);
  }
}

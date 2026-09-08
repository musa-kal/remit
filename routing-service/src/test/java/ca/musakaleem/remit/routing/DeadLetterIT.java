package ca.musakaleem.remit.routing;

import static ca.musakaleem.remit.routing.support.TestInstructions.instruction;
import static ca.musakaleem.remit.routing.support.TestInstructions.withRemittance;
import static org.assertj.core.api.Assertions.assertThat;

import ca.musakaleem.remit.common.Topics;
import ca.musakaleem.remit.common.event.Channel;
import ca.musakaleem.remit.common.event.EventHeaders;
import ca.musakaleem.remit.common.event.EventType;
import ca.musakaleem.remit.common.event.InstructionStatus;
import ca.musakaleem.remit.common.event.InstructionStatusEvent;
import ca.musakaleem.remit.common.event.PaymentInstruction;
import ca.musakaleem.remit.routing.error.NonRetryableRoutingException;
import ca.musakaleem.remit.routing.error.RoutingException;
import ca.musakaleem.remit.routing.support.AbstractRoutingIT;
import ca.musakaleem.remit.routing.support.TopicReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.serializer.DeserializationException;

/** Retries, the dead-letter topic and poison pills (FR-RTE-03, FR-RTE-04, spec 10.2). */
class DeadLetterIT extends AbstractRoutingIT {

  @Test
  void forceFailIsDeadLetteredAfterExactlyThreeAttemptsWithOriginalAndExceptionHeaders() {
    PaymentInstruction forceFail =
        withRemittance(instruction(Channel.EFT, "42.00", "CAD"), "FORCE_FAIL");
    String key = forceFail.idempotencyKey();
    ProducerRecord<String, Object> sent = publish(forceFail);

    ConsumerRecord<String, String> dead = awaitDeadLetter(forceFail.batchId().toString());

    assertThat(ledgerRow(key)).containsEntry("attempts", 3).containsEntry("status", "ROUTING");
    assertThat(dead.key()).isEqualTo(forceFail.debtor().account());
    assertThat(parse(dead.value(), PaymentInstruction.class)).isEqualTo(forceFail);
    for (String name :
        new String[] {
          EventHeaders.EVENT_ID,
          EventHeaders.EVENT_TYPE,
          EventHeaders.OCCURRED_AT,
          EventHeaders.CORRELATION_ID,
          EventHeaders.SCHEMA_VERSION
        }) {
      assertThat(header(dead, name)).as(name).isEqualTo(value(sent.headers().lastHeader(name)));
    }
    assertThat(header(dead, KafkaHeaders.DLT_EXCEPTION_FQCN))
        .isEqualTo(ListenerExecutionFailedException.class.getName());
    assertThat(header(dead, KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN))
        .isEqualTo(RoutingException.class.getName());
    assertThat(header(dead, KafkaHeaders.DLT_EXCEPTION_MESSAGE)).contains("FORCE_FAIL");
    assertThat(header(dead, KafkaHeaders.DLT_EXCEPTION_STACKTRACE)).isNotBlank();
    assertThat(header(dead, KafkaHeaders.DLT_ORIGINAL_TOPIC)).isEqualTo(Topics.INSTRUCTIONS);
    assertThat(header(dead, KafkaHeaders.DLT_ORIGINAL_CONSUMER_GROUP)).isEqualTo("routing-service");
    int originalPartition = intHeader(dead, KafkaHeaders.DLT_ORIGINAL_PARTITION);
    assertThat(dead.partition()).as("same partition as the source").isEqualTo(originalPartition);
    assertThat(dead.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_OFFSET)).isNotNull();

    assertThat(awaitStatuses(key, 1)).containsExactly(InstructionStatus.DEAD_LETTERED);
    InstructionStatusEvent event = statusEvents(key).get(0);
    assertThat(event.detail()).contains("FORCE_FAIL");
    assertThat(event.batchId()).isEqualTo(forceFail.batchId());
    assertThat(TopicReader.byKey(Topics.ROUTED_EFT, key)).isEmpty();
  }

  @Test
  void aChannelRuleViolationIsDeadLetteredWithoutRetries() {
    PaymentInstruction usdInterac = instruction(Channel.INTERAC, "100.00", "USD");
    String key = usdInterac.idempotencyKey();

    publish(usdInterac);
    ConsumerRecord<String, String> dead = awaitDeadLetter(usdInterac.batchId().toString());

    assertThat(ledgerRow(key)).containsEntry("attempts", 1).containsEntry("status", "ROUTING");
    assertThat(header(dead, KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN))
        .isEqualTo(NonRetryableRoutingException.class.getName());
    assertThat(header(dead, KafkaHeaders.DLT_EXCEPTION_MESSAGE))
        .contains("INTERAC does not accept currency USD");
    assertThat(awaitStatuses(key, 1)).containsExactly(InstructionStatus.DEAD_LETTERED);
    assertThat(statusEvents(key).get(0).detail()).isEqualTo("INTERAC does not accept currency USD");
  }

  @Test
  void aPoisonPillIsDeadLetteredAndTheNextRecordOnItsPartitionIsStillRouted() {
    int partition = 1;
    String poisonBatch = UUID.randomUUID().toString();
    byte[] malformed = "{\"idempotencyKey\": \"abc\", broken".getBytes(StandardCharsets.UTF_8);
    send(
        new ProducerRecord<>(
            Topics.INSTRUCTIONS,
            partition,
            "1000009999",
            malformed,
            EventHeaders.build(EventType.PAYMENT_INSTRUCTION, poisonBatch, Instant.now())));
    PaymentInstruction valid = instruction(Channel.EFT, "12.34", "CAD");
    publish(partition, valid);

    assertThat(awaitStatuses(valid.idempotencyKey(), 1)).containsExactly(InstructionStatus.ROUTED);
    ConsumerRecord<String, String> dead = awaitDeadLetter(poisonBatch);
    assertThat(dead.value()).isEqualTo(new String(malformed, StandardCharsets.UTF_8));
    assertThat(dead.key()).isEqualTo("1000009999");
    assertThat(dead.partition()).isEqualTo(partition);
    assertThat(header(dead, KafkaHeaders.DLT_EXCEPTION_FQCN))
        .isEqualTo(DeserializationException.class.getName());
    assertThat(header(dead, KafkaHeaders.DLT_ORIGINAL_TOPIC)).isEqualTo(Topics.INSTRUCTIONS);
    assertThat(intHeader(dead, KafkaHeaders.DLT_ORIGINAL_PARTITION)).isEqualTo(partition);
    assertThat(dead.headers().lastHeader("springDeserializerExceptionValue")).isNull();
  }

  private static String header(ConsumerRecord<String, String> record, String name) {
    return EventHeaders.lastValue(record.headers(), name)
        .orElseThrow(() -> new AssertionError(name));
  }

  private static int intHeader(ConsumerRecord<String, String> record, String name) {
    return ByteBuffer.wrap(record.headers().lastHeader(name).value()).getInt();
  }

  private static String value(Header header) {
    return new String(header.value(), StandardCharsets.UTF_8);
  }
}

package ca.musakaleem.remit.routing.publish;

import static ca.musakaleem.remit.routing.support.TestInstructions.instruction;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ca.musakaleem.remit.common.event.Channel;
import ca.musakaleem.remit.common.event.EventHeaders;
import ca.musakaleem.remit.common.event.InstructionStatus;
import ca.musakaleem.remit.common.event.InstructionStatusEvent;
import ca.musakaleem.remit.common.event.PaymentInstruction;
import ca.musakaleem.remit.routing.config.RoutingProperties;
import ca.musakaleem.remit.routing.error.RoutingException;
import ca.musakaleem.remit.routing.ledger.ProcessingStatus;
import ca.musakaleem.remit.routing.rules.RoutingDecision;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Headers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

class RoutingEventPublisherTest {

  private static final Instant NOW = Instant.parse("2026-10-15T13:30:00.123Z");

  private final PaymentInstruction instruction = instruction(Channel.WIRE, "1500000.00", "USD");

  @SuppressWarnings("unchecked")
  private final KafkaTemplate<String, Object> template = mock(KafkaTemplate.class);

  @SuppressWarnings("unchecked")
  private final ArgumentCaptor<ProducerRecord<String, Object>> sent =
      ArgumentCaptor.forClass(ProducerRecord.class);

  private RoutingEventPublisher publisher;

  @BeforeEach
  void setUp() {
    publisher = publisher(Duration.ofSeconds(5));
  }

  @Test
  void publishesTheRoutedInstructionKeyedByIdempotencyKeyWithStandardHeaders() {
    acknowledge();

    publisher.publishRouted(
        instruction,
        new RoutingDecision(
            Channel.WIRE, "payments.routed.wire", ProcessingStatus.REQUIRES_REVIEW));

    verify(template).send(sent.capture());
    ProducerRecord<String, Object> record = sent.getValue();
    assertThat(record.topic()).isEqualTo("payments.routed.wire");
    assertThat(record.key()).isEqualTo(instruction.idempotencyKey());
    assertThat(record.value()).isSameAs(instruction);
    Headers headers = record.headers();
    assertThat(header(headers, EventHeaders.EVENT_TYPE)).isEqualTo("INSTRUCTION_ROUTED");
    assertThat(header(headers, EventHeaders.CORRELATION_ID))
        .isEqualTo(instruction.batchId().toString());
    assertThat(header(headers, EventHeaders.OCCURRED_AT)).isEqualTo(NOW.toString());
    assertThat(header(headers, EventHeaders.SCHEMA_VERSION)).isEqualTo("1");
    assertThat(header(headers, EventHeaders.EVENT_ID)).hasSize(36);
    assertThat(header(headers, RoutingEventPublisher.ROUTING_STATUS_HEADER))
        .isEqualTo("REQUIRES_REVIEW");
  }

  @Test
  void publishesAStatusEventToTheStatusTopic() {
    acknowledge();

    publisher.publishStatus(instruction, InstructionStatus.DUPLICATE, "already ROUTED");

    verify(template).send(sent.capture());
    ProducerRecord<String, Object> record = sent.getValue();
    assertThat(record.topic()).isEqualTo("payments.status-events");
    assertThat(record.key()).isEqualTo(instruction.idempotencyKey());
    assertThat(record.value())
        .isEqualTo(
            new InstructionStatusEvent(
                instruction.idempotencyKey(),
                instruction.batchId(),
                InstructionStatus.DUPLICATE,
                Channel.WIRE,
                "already ROUTED",
                NOW));
    assertThat(header(record.headers(), EventHeaders.EVENT_TYPE)).isEqualTo("INSTRUCTION_STATUS");
    assertThat(header(record.headers(), EventHeaders.CORRELATION_ID))
        .isEqualTo(instruction.batchId().toString());
  }

  @Test
  void aRejectedSendBecomesARetryableRoutingException() {
    when(template.send(any(ProducerRecord.class)))
        .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

    assertThatThrownBy(() -> publisher.publishStatus(instruction, InstructionStatus.ROUTED, null))
        .isInstanceOf(RoutingException.class)
        .hasMessage("Kafka rejected ROUTED status event")
        .hasRootCauseMessage("broker down");
  }

  @Test
  void anUnacknowledgedSendTimesOut() {
    RoutingEventPublisher impatient = publisher(Duration.ofMillis(20));
    when(template.send(any(ProducerRecord.class))).thenReturn(new CompletableFuture<>());
    RoutingDecision decision =
        new RoutingDecision(Channel.WIRE, "payments.routed.wire", ProcessingStatus.ROUTED);

    assertThatThrownBy(() -> impatient.publishRouted(instruction, decision))
        .isInstanceOf(RoutingException.class)
        .hasMessageStartingWith("Timed out after PT0.02S publishing routed instruction");
  }

  @Test
  void anInterruptedWaitKeepsTheInterruptFlag() {
    when(template.send(any(ProducerRecord.class))).thenReturn(new CompletableFuture<>());

    Thread.currentThread().interrupt();
    try {
      assertThatThrownBy(() -> publisher.publishStatus(instruction, InstructionStatus.ROUTED, null))
          .isInstanceOf(RoutingException.class)
          .hasMessageStartingWith("Interrupted");
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
  }

  private void acknowledge() {
    CompletableFuture<SendResult<String, Object>> done = CompletableFuture.completedFuture(null);
    when(template.send(any(ProducerRecord.class))).thenReturn(done);
  }

  private RoutingEventPublisher publisher(Duration timeout) {
    return new RoutingEventPublisher(
        template,
        Clock.fixed(NOW, ZoneOffset.UTC),
        new RoutingProperties(Map.of(), new BigDecimal("1000000.00"), false, timeout));
  }

  private static String header(Headers headers, String name) {
    return EventHeaders.lastValue(headers, name).orElseThrow();
  }
}

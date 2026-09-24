package ca.musakaleem.remit.intake.publish;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ca.musakaleem.remit.common.Topics;
import ca.musakaleem.remit.common.event.BatchEvent;
import ca.musakaleem.remit.common.event.BatchStatus;
import ca.musakaleem.remit.common.event.Channel;
import ca.musakaleem.remit.common.event.EventHeaders;
import ca.musakaleem.remit.common.event.InstructionRejected;
import ca.musakaleem.remit.common.event.Party;
import ca.musakaleem.remit.common.event.PaymentInstruction;
import ca.musakaleem.remit.intake.config.IntakeProperties;
import ca.musakaleem.remit.intake.validation.ReasonCode;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

class IntakeEventPublisherTest {

  private static final UUID BATCH_ID = UUID.fromString("7f1c9a5e-2d3b-4c8e-9f10-1a2b3c4d5e6f");
  private static final Instant NOW = Instant.parse("2026-10-15T13:30:00Z");

  private KafkaTemplate<String, Object> kafka;
  private IntakeEventPublisher publisher;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    kafka = mock(KafkaTemplate.class);
    publisher =
        new IntakeEventPublisher(
            kafka,
            Clock.fixed(NOW, ZoneOffset.UTC),
            new IntakeProperties(Path.of("staging"), 100, 1000, Duration.ofMillis(200)));
  }

  @Test
  void instructionIsKeyedByDebtorAccountWithStandardHeaders() {
    when(kafka.send(any(ProducerRecord.class))).thenReturn(new CompletableFuture<>());

    publisher.publishInstruction(instruction());

    ProducerRecord<String, Object> sent = captureSent();
    assertThat(sent.topic()).isEqualTo(Topics.INSTRUCTIONS);
    assertThat(sent.key()).isEqualTo("1234567");
    assertThat(header(sent, EventHeaders.EVENT_TYPE)).isEqualTo("PAYMENT_INSTRUCTION");
    assertThat(header(sent, EventHeaders.CORRELATION_ID)).isEqualTo(BATCH_ID.toString());
    assertThat(header(sent, EventHeaders.OCCURRED_AT)).isEqualTo("2026-10-15T13:30:00Z");
    assertThat(header(sent, EventHeaders.SCHEMA_VERSION)).isEqualTo("1");
    assertThat(UUID.fromString(header(sent, EventHeaders.EVENT_ID))).isNotNull();
  }

  @Test
  void rejectionIsKeyedByBatchIdAndAwaited() {
    when(kafka.send(any(ProducerRecord.class))).thenReturn(ack());

    publisher.publishRejection(
        new InstructionRejected(BATCH_ID, "ACME01", 4, "R-1", List.of("INVALID_AMOUNT"), "R-1,x"));

    ProducerRecord<String, Object> sent = captureSent();
    assertThat(sent.topic()).isEqualTo(Topics.REJECTED);
    assertThat(sent.key()).isEqualTo(BATCH_ID.toString());
    assertThat(header(sent, EventHeaders.EVENT_TYPE)).isEqualTo("INSTRUCTION_REJECTED");
  }

  @Test
  void receivedBatchEventUsesItsOwnTimestamp() {
    when(kafka.send(any(ProducerRecord.class))).thenReturn(ack());
    Instant occurred = NOW.minusSeconds(60);

    publisher.publishBatchEvent(batchEvent(BatchStatus.RECEIVED, occurred), null);

    ProducerRecord<String, Object> sent = captureSent();
    assertThat(sent.topic()).isEqualTo(Topics.BATCH_EVENTS);
    assertThat(sent.key()).isEqualTo(BATCH_ID.toString());
    assertThat(header(sent, EventHeaders.EVENT_TYPE)).isEqualTo("BATCH_RECEIVED");
    assertThat(header(sent, EventHeaders.OCCURRED_AT)).isEqualTo(occurred.toString());
    assertThat(sent.headers().lastHeader(EventHeaders.REASON_CODE)).isNull();
  }

  @Test
  void failedBatchEventCarriesTheReasonCodeHeader() {
    when(kafka.send(any(ProducerRecord.class))).thenReturn(ack());

    publisher.publishBatchEvent(
        batchEvent(BatchStatus.FAILED, NOW), ReasonCode.CONTROL_TOTAL_MISMATCH);

    ProducerRecord<String, Object> sent = captureSent();
    assertThat(header(sent, EventHeaders.EVENT_TYPE)).isEqualTo("BATCH_COMPLETED");
    assertThat(header(sent, EventHeaders.REASON_CODE)).isEqualTo("CONTROL_TOTAL_MISMATCH");
  }

  @Test
  void awaitAllReturnsWhenEverySendIsAcknowledged() {
    assertThatCode(() -> publisher.awaitAll(List.of(ack(), ack()))).doesNotThrowAnyException();
  }

  @Test
  void awaitAllFailsWhenKafkaRejectsASend() {
    CompletableFuture<SendResult<String, Object>> failed = new CompletableFuture<>();
    failed.completeExceptionally(new IllegalStateException("broker down"));

    assertThatThrownBy(() -> publisher.awaitAll(List.of(ack(), failed)))
        .isInstanceOf(EventPublishException.class)
        .hasMessageContaining("Kafka rejected 2 instructions")
        .hasRootCauseMessage("broker down");
  }

  @Test
  void awaitAllFailsOnTimeout() {
    assertThatThrownBy(() -> publisher.awaitAll(List.of(new CompletableFuture<>())))
        .isInstanceOf(EventPublishException.class)
        .hasMessageContaining("Timed out");
  }

  @Test
  void awaitRestoresTheInterruptFlag() {
    Thread.currentThread().interrupt();
    try {
      assertThatThrownBy(() -> publisher.awaitAll(List.of(new CompletableFuture<>())))
          .isInstanceOf(EventPublishException.class)
          .hasMessageContaining("Interrupted");
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
  }

  @SuppressWarnings("unchecked")
  private ProducerRecord<String, Object> captureSent() {
    ArgumentCaptor<ProducerRecord<String, Object>> captor =
        ArgumentCaptor.forClass(ProducerRecord.class);
    verify(kafka).send(captor.capture());
    return captor.getValue();
  }

  private static String header(ProducerRecord<String, Object> record, String name) {
    return EventHeaders.lastValue(record.headers(), name).orElse(null);
  }

  private static CompletableFuture<SendResult<String, Object>> ack() {
    return CompletableFuture.completedFuture(null);
  }

  private static PaymentInstruction instruction() {
    return new PaymentInstruction(
        "key",
        BATCH_ID,
        "ACME01",
        "R-1",
        new Party("Jane Doe", "001", "00011", "1234567"),
        new Party("John Roe", "002", "00022", "7654321"),
        new BigDecimal("10.00"),
        "CAD",
        Channel.EFT,
        LocalDate.of(2026, 10, 15),
        null,
        0);
  }

  private static BatchEvent batchEvent(BatchStatus status, Instant at) {
    return new BatchEvent(
        BATCH_ID,
        "ACME01",
        "PAY_ACME01_20261015093000.csv",
        "h",
        status,
        10,
        0,
        0,
        new BigDecimal("10.00"),
        at);
  }
}

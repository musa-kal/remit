package ca.musakaleem.remit.api.deadletter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ca.musakaleem.remit.api.support.TestEvents;
import ca.musakaleem.remit.api.web.NotFoundException;
import ca.musakaleem.remit.api.web.ServiceUnavailableException;
import ca.musakaleem.remit.common.RemitJson;
import ca.musakaleem.remit.common.event.PaymentInstruction;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

class ReplayServiceTest {

  private static final Instant NOW = Instant.parse("2026-10-15T14:00:00Z");

  private final DeadLetterRepository repository = mock(DeadLetterRepository.class);

  @SuppressWarnings("unchecked")
  private final KafkaTemplate<String, Object> kafka = mock(KafkaTemplate.class);

  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
  private final ReplayProperties properties = new ReplayProperties(3, Duration.ofMillis(200));
  private final ReplayService service =
      new ReplayService(
          repository,
          new DeadLetterRecordParser(),
          new ReplayPublisher(kafka, properties),
          clock,
          properties);
  private final PaymentInstruction instruction = TestEvents.instruction(UUID.randomUUID(), 0);

  @Test
  void publishesTheStoredInstructionWithTheNextReplayCountKeyedByDebtorAccount() throws Exception {
    DeadLetter deadLetter = deadLetter();
    when(repository.findByIdForUpdate(5L)).thenReturn(Optional.of(deadLetter));
    when(kafka.send(any(ProducerRecord.class)))
        .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

    DeadLetter replayed = service.replay(5L);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<ProducerRecord<String, Object>> sent =
        ArgumentCaptor.forClass(ProducerRecord.class);
    verify(kafka).send(sent.capture());
    ProducerRecord<String, Object> record = sent.getValue();
    assertThat(record.topic()).isEqualTo("payments.instructions");
    assertThat(record.key()).isEqualTo(TestEvents.DEBTOR_ACCOUNT);
    assertThat(((PaymentInstruction) record.value()).replayCount()).isEqualTo(1);
    assertThat(((PaymentInstruction) record.value()).idempotencyKey())
        .isEqualTo(instruction.idempotencyKey());
    assertThat(header(record, "eventType")).isEqualTo("PAYMENT_INSTRUCTION");
    assertThat(header(record, "correlationId")).isEqualTo(instruction.batchId().toString());
    assertThat(header(record, "occurredAt")).isEqualTo(NOW.toString());
    assertThat(header(record, "schemaVersion")).isEqualTo("1");
    assertThat(UUID.fromString(header(record, "eventId"))).isNotNull();
    assertThat(replayed.getStatus()).isEqualTo(DeadLetterStatus.REPLAY_REQUESTED);
    assertThat(replayed.getLastReplayedAt()).isEqualTo(NOW);
  }

  @Test
  void aKafkaFailureBecomes503SoTheTransactionRollsBack() {
    when(repository.findByIdForUpdate(5L)).thenReturn(Optional.of(deadLetter()));
    when(kafka.send(any(ProducerRecord.class)))
        .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

    assertThatThrownBy(() -> service.replay(5L))
        .isInstanceOf(ServiceUnavailableException.class)
        .hasMessageStartingWith("Kafka did not acknowledge the replay");
  }

  @Test
  void aSlowBrokerTimesOutAs503() {
    when(repository.findByIdForUpdate(5L)).thenReturn(Optional.of(deadLetter()));
    when(kafka.send(any(ProducerRecord.class))).thenReturn(new CompletableFuture<>());

    assertThatThrownBy(() -> service.replay(5L)).isInstanceOf(ServiceUnavailableException.class);
  }

  @Test
  void anInterruptedPublishKeepsTheInterruptFlag() {
    when(repository.findByIdForUpdate(5L)).thenReturn(Optional.of(deadLetter()));
    when(kafka.send(any(ProducerRecord.class))).thenReturn(new CompletableFuture<>());

    Thread.currentThread().interrupt();
    try {
      assertThatThrownBy(() -> service.replay(5L))
          .isInstanceOf(ServiceUnavailableException.class)
          .hasMessageContaining("Interrupted");
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void anUnknownIdIs404AndNothingIsPublished() {
    when(repository.findByIdForUpdate(anyLong())).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.replay(9L))
        .isInstanceOf(NotFoundException.class)
        .hasMessage("Dead letter 9 not found");
    verify(kafka, never()).send(any(ProducerRecord.class));
  }

  @Test
  void aRefusedReplayPublishesNothing() {
    DeadLetter deadLetter = deadLetter();
    deadLetter.requestReplay(3, NOW);
    when(repository.findByIdForUpdate(5L)).thenReturn(Optional.of(deadLetter));

    assertThatThrownBy(() -> service.replay(5L)).isInstanceOf(ReplayRejectedException.class);
    verify(kafka, never()).send(any(ProducerRecord.class));
  }

  private DeadLetter deadLetter() {
    try {
      return DeadLetter.from(
          new DeadLetterRecord(
              "payments.instructions",
              0,
              11,
              "X",
              "boom",
              instruction,
              RemitJson.objectMapper().writeValueAsString(instruction),
              NOW.minusSeconds(60),
              instruction.batchId().toString()),
          3);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String header(ProducerRecord<String, Object> record, String name) {
    return new String(
        record.headers().lastHeader(name).value(), java.nio.charset.StandardCharsets.UTF_8);
  }
}

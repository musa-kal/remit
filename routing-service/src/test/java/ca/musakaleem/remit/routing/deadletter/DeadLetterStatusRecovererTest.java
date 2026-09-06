package ca.musakaleem.remit.routing.deadletter;

import static ca.musakaleem.remit.routing.support.TestInstructions.instruction;
import static ca.musakaleem.remit.routing.support.TestInstructions.withKeyBatchAndAmount;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import ca.musakaleem.remit.common.event.Channel;
import ca.musakaleem.remit.common.event.InstructionStatus;
import ca.musakaleem.remit.common.event.PaymentInstruction;
import ca.musakaleem.remit.routing.error.RoutingException;
import ca.musakaleem.remit.routing.publish.RoutingEventPublisher;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.slf4j.MDC;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.listener.ConsumerAwareRecordRecoverer;
import org.springframework.kafka.listener.ListenerExecutionFailedException;

class DeadLetterStatusRecovererTest {

  private final ConsumerAwareRecordRecoverer deadLetters = mock(ConsumerAwareRecordRecoverer.class);
  private final RoutingEventPublisher publisher = mock(RoutingEventPublisher.class);
  private final Consumer<?, ?> consumer = mock(Consumer.class);

  private final DeadLetterStatusRecoverer recoverer =
      new DeadLetterStatusRecoverer(deadLetters, publisher);

  @Test
  void writesTheDltRecordThenPublishesDeadLetteredWithTheRootCause() {
    PaymentInstruction instruction = instruction(Channel.EFT, "10.00", "CAD");
    ConsumerRecord<String, Object> record = record(instruction);
    Exception failure =
        new ListenerExecutionFailedException(
            "Listener failed", new RoutingException("Forced failure: FORCE_FAIL"));

    recoverer.accept(record, consumer, failure);

    InOrder order = inOrder(deadLetters, publisher);
    order.verify(deadLetters).accept(record, consumer, failure);
    order
        .verify(publisher)
        .publishStatus(instruction, InstructionStatus.DEAD_LETTERED, "Forced failure: FORCE_FAIL");
    assertThat(MDC.get("idempotencyKey")).isNull();
  }

  @Test
  void aPoisonPillGetsOnlyItsDltRecord() {
    ConsumerRecord<String, Object> record = record(null);
    Exception failure = new KafkaException("cannot deserialize");

    recoverer.accept(record, consumer, failure);

    verify(deadLetters).accept(record, consumer, failure);
    verifyNoInteractions(publisher);
  }

  @Test
  void anInstructionWithoutKeyOrBatchGetsOnlyItsDltRecord() {
    PaymentInstruction valid = instruction(Channel.EFT, "10.00", "CAD");
    PaymentInstruction noKey = withKeyBatchAndAmount(valid, null, valid.batchId(), valid.amount());
    PaymentInstruction noBatch =
        withKeyBatchAndAmount(valid, valid.idempotencyKey(), null, valid.amount());

    recoverer.accept(record(noKey), consumer, new IllegalStateException("x"));
    recoverer.accept(record(noBatch), consumer, new IllegalStateException("x"));

    verifyNoInteractions(publisher);
  }

  @Test
  void noStatusEventWhenTheDltPublishFails() {
    ConsumerRecord<String, Object> record = record(instruction(Channel.EFT, "10.00", "CAD"));
    doThrow(new KafkaException("DLT down")).when(deadLetters).accept(any(), any(), any());

    assertThatThrownBy(() -> recoverer.accept(record, consumer, new RoutingException("x")))
        .hasMessage("DLT down");

    verifyNoInteractions(publisher);
  }

  @Test
  void detailIsCutToFiveHundredCharacters() {
    String detail = DeadLetterStatusRecoverer.detail(new RoutingException("x".repeat(600)));

    assertThat(detail).hasSize(500).endsWith("...");
  }

  @Test
  void detailFallsBackToTheExceptionClass() {
    assertThat(DeadLetterStatusRecoverer.detail(new IllegalStateException()))
        .isEqualTo("java.lang.IllegalStateException");
  }

  private static ConsumerRecord<String, Object> record(Object value) {
    return new ConsumerRecord<>("payments.instructions", 1, 7L, "1000001234", value);
  }
}

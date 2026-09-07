package ca.musakaleem.remit.routing.config;

import static ca.musakaleem.remit.routing.support.TestInstructions.instruction;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ca.musakaleem.remit.common.event.Channel;
import ca.musakaleem.remit.common.event.InstructionStatus;
import ca.musakaleem.remit.common.event.PaymentInstruction;
import ca.musakaleem.remit.routing.error.NonRetryableRoutingException;
import ca.musakaleem.remit.routing.error.RoutingException;
import ca.musakaleem.remit.routing.publish.RoutingEventPublisher;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.BackOffExecution;

class ErrorHandlingConfigTest {

  private final ErrorHandlingConfig config = new ErrorHandlingConfig();
  private final DeadLetterPublishingRecoverer deadLetters =
      mock(DeadLetterPublishingRecoverer.class);
  private final RoutingEventPublisher publisher = mock(RoutingEventPublisher.class);
  private final Consumer<?, ?> consumer = mock(Consumer.class);
  private final MessageListenerContainer container = mock(MessageListenerContainer.class);
  private final DefaultErrorHandler handler = config.errorHandler(deadLetters, publisher);

  private final PaymentInstruction instruction = instruction(Channel.EFT, "10.00", "CAD");
  private final ConsumerRecord<String, Object> record =
      new ConsumerRecord<>("payments.instructions", 2, 11L, "1000001234", instruction);

  @Test
  void backsOffOneThenTwoSecondsThenStopsAfterThreeAttempts() {
    BackOff backOff = ErrorHandlingConfig.backOff();
    BackOffExecution execution = backOff.start();

    assertThat(backOff).isInstanceOf(ExponentialBackOffWithMaxRetries.class);
    assertThat(((ExponentialBackOffWithMaxRetries) backOff).getMaxRetries()).isEqualTo(2);
    assertThat(execution.nextBackOff()).isEqualTo(1_000L);
    assertThat(execution.nextBackOff()).isEqualTo(2_000L);
    assertThat(execution.nextBackOff()).isEqualTo(BackOffExecution.STOP);
  }

  @Test
  void aRetryableFailureIsRetriedTwiceThenDeadLettered() {
    Exception failure = listenerFailure(new RoutingException("Forced failure: FORCE_FAIL"));

    assertThatThrownBy(() -> deliver(failure)).hasMessage("Record in retry and not yet recovered");
    assertThatThrownBy(() -> deliver(failure)).hasMessage("Record in retry and not yet recovered");
    verify(deadLetters, never()).accept(any(), any(), any());
    verify(consumer, times(2)).seek(new TopicPartition("payments.instructions", 2), 11L);

    deliver(failure);
    verify(deadLetters).accept(record, consumer, failure);
    verify(publisher)
        .publishStatus(
            eq(instruction), eq(InstructionStatus.DEAD_LETTERED), startsWith("Forced failure"));
  }

  @Test
  void aNonRetryableFailureIsDeadLetteredOnTheFirstAttempt() {
    Exception failure =
        listenerFailure(new NonRetryableRoutingException("INTERAC does not accept currency USD"));

    deliver(failure);

    verify(deadLetters).accept(record, consumer, failure);
    verify(consumer, never()).seek(any(), any(Long.class));
    verify(publisher)
        .publishStatus(
            instruction, InstructionStatus.DEAD_LETTERED, "INTERAC does not accept currency USD");
  }

  @Test
  void aDeserializationFailureIsDeadLetteredOnTheFirstAttempt() {
    Exception failure =
        listenerFailure(
            new DeserializationException("bad json", new byte[] {'{'}, false, new Exception()));

    deliver(failure);

    verify(deadLetters).accept(record, consumer, failure);
  }

  @Test
  @SuppressWarnings("unchecked")
  void theRecovererWritesToTheInstructionsDltKeepingKeyValueAndPartition() {
    KafkaTemplate<Object, Object> template = mock(KafkaTemplate.class);
    when(template.send(any(ProducerRecord.class)))
        .thenReturn(CompletableFuture.completedFuture(null));
    ArgumentCaptor<ProducerRecord<Object, Object>> sent =
        ArgumentCaptor.forClass(ProducerRecord.class);

    config
        .deadLetterPublishingRecoverer(template, 3)
        .accept(record, null, listenerFailure(new RoutingException("boom")));

    verify(template).send(sent.capture());
    assertThat(sent.getValue().topic()).isEqualTo("payments.instructions.DLT");
    assertThat(sent.getValue().partition()).isEqualTo(2);
    assertThat(sent.getValue().key()).isEqualTo("1000001234");
    assertThat(sent.getValue().value()).isSameAs(instruction);
    assertThat(sent.getValue().headers().lastHeader("kafka_dlt-exception-message")).isNotNull();
  }

  private void deliver(Exception failure) {
    handler.handleRemaining(failure, List.of(record), consumer, container);
  }

  private static Exception listenerFailure(Exception cause) {
    return new ListenerExecutionFailedException("Listener method threw exception", cause);
  }
}

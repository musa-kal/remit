package ca.musakaleem.remit.routing.consumer;

import static ca.musakaleem.remit.routing.support.TestInstructions.instruction;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import ca.musakaleem.remit.common.event.Channel;
import ca.musakaleem.remit.common.event.EventHeaders;
import ca.musakaleem.remit.common.event.PaymentInstruction;
import ca.musakaleem.remit.routing.error.NonRetryableRoutingException;
import ca.musakaleem.remit.routing.error.RoutingException;
import ca.musakaleem.remit.routing.service.RoutingService;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class InstructionListenerTest {

  private final RoutingService routingService = mock(RoutingService.class);
  private final InstructionListener listener = new InstructionListener(routingService);

  @Test
  void routesTheInstructionWithCorrelationIdAndKeyInTheMdc() {
    PaymentInstruction instruction = instruction(Channel.EFT, "10.00", "CAD");
    Map<String, String> seen = new HashMap<>();
    doAnswer(
            call -> {
              seen.put("correlationId", MDC.get("correlationId"));
              seen.put("idempotencyKey", MDC.get("idempotencyKey"));
              return null;
            })
        .when(routingService)
        .route(instruction);

    listener.onInstruction(record(instruction, instruction.batchId().toString()));

    assertThat(seen)
        .containsEntry("correlationId", instruction.batchId().toString())
        .containsEntry("idempotencyKey", instruction.idempotencyKey());
    assertThat(MDC.get("correlationId")).isNull();
    assertThat(MDC.get("idempotencyKey")).isNull();
  }

  @Test
  void clearsTheMdcWhenRoutingFails() {
    PaymentInstruction instruction = instruction(Channel.EFT, "10.00", "CAD");
    doThrow(new RoutingException("boom")).when(routingService).route(any());

    assertThatThrownBy(() -> listener.onInstruction(record(instruction, "batch")))
        .isInstanceOf(RoutingException.class);

    assertThat(MDC.get("correlationId")).isNull();
    assertThat(MDC.get("idempotencyKey")).isNull();
  }

  @Test
  void passesAnEmptyRecordOnSoTheServiceRejectsIt() {
    listener.onInstruction(record(null, null));

    verify(routingService).route(null);
  }

  @Test
  void rejectsAPayloadOfAnotherType() {
    ConsumerRecord<String, Object> record =
        new ConsumerRecord<>("payments.instructions", 0, 0L, "key", Map.of("a", 1));

    assertThatThrownBy(() -> listener.onInstruction(record))
        .isInstanceOf(NonRetryableRoutingException.class)
        .hasMessageStartingWith("Expected a PaymentInstruction");
    verifyNoInteractions(routingService);
  }

  private static ConsumerRecord<String, Object> record(Object value, String correlationId) {
    ConsumerRecord<String, Object> record =
        new ConsumerRecord<>("payments.instructions", 0, 0L, "1000001234", value);
    if (correlationId != null) {
      record.headers().add(EventHeaders.header(EventHeaders.CORRELATION_ID, correlationId));
    }
    return record;
  }
}

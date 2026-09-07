package ca.musakaleem.remit.routing.consumer;

import ca.musakaleem.remit.common.Topics;
import ca.musakaleem.remit.common.event.EventHeaders;
import ca.musakaleem.remit.common.event.PaymentInstruction;
import ca.musakaleem.remit.routing.error.NonRetryableRoutingException;
import ca.musakaleem.remit.routing.logging.MdcKeys;
import ca.musakaleem.remit.routing.service.RoutingService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code payments.instructions} (FR-RTE-01). Group, ack mode ({@code RECORD}: the offset
 * is committed after each record the listener returns from), disabled auto commit and concurrency
 * come from application.yml. Records that could not be deserialized never reach this method: the
 * error handler dead-letters them directly.
 */
@Component
public class InstructionListener {

  /** Listener container id. The consumer group is {@code spring.kafka.consumer.group-id}. */
  public static final String LISTENER_ID = "instruction-listener";

  private final RoutingService routingService;

  /**
   * Creates the listener.
   *
   * @param routingService the routing algorithm
   */
  public InstructionListener(RoutingService routingService) {
    this.routingService = routingService;
  }

  /**
   * Routes one instruction with the correlation id and idempotency key in the MDC.
   *
   * @param record the consumed record
   */
  @KafkaListener(id = LISTENER_ID, idIsGroup = false, topics = Topics.INSTRUCTIONS)
  public void onInstruction(ConsumerRecord<String, ?> record) {
    Object value = record.value();
    if (value != null && !(value instanceof PaymentInstruction)) {
      // Only possible with a type header naming another class of a trusted package.
      throw new NonRetryableRoutingException(
          "Expected a PaymentInstruction but got " + value.getClass().getName());
    }
    PaymentInstruction instruction = (PaymentInstruction) value;
    putMdc(record, instruction);
    try {
      routingService.route(instruction);
    } finally {
      MDC.remove(MdcKeys.CORRELATION_ID);
      MDC.remove(MdcKeys.IDEMPOTENCY_KEY);
    }
  }

  private static void putMdc(ConsumerRecord<String, ?> record, PaymentInstruction instruction) {
    EventHeaders.lastValue(record.headers(), EventHeaders.CORRELATION_ID)
        .ifPresent(id -> MDC.put(MdcKeys.CORRELATION_ID, id));
    if (instruction != null && instruction.idempotencyKey() != null) {
      MDC.put(MdcKeys.IDEMPOTENCY_KEY, instruction.idempotencyKey());
    }
  }
}

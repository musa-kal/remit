package ca.musakaleem.remit.routing;

import static ca.musakaleem.remit.routing.support.TestInstructions.instruction;
import static ca.musakaleem.remit.routing.support.TestInstructions.withReplayCount;
import static org.assertj.core.api.Assertions.assertThat;

import ca.musakaleem.remit.common.Topics;
import ca.musakaleem.remit.common.event.Channel;
import ca.musakaleem.remit.common.event.EventHeaders;
import ca.musakaleem.remit.common.event.InstructionStatus;
import ca.musakaleem.remit.common.event.InstructionStatusEvent;
import ca.musakaleem.remit.common.event.PaymentInstruction;
import ca.musakaleem.remit.routing.support.AbstractRoutingIT;
import ca.musakaleem.remit.routing.support.TopicReader;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

/** The FR-RTE-02 algorithm end to end against MySQL and Kafka. */
class RoutingFlowIT extends AbstractRoutingIT {

  @Test
  void anInstructionPublishedTwiceIsRoutedOnceAndReportedAsDuplicateOnce() {
    PaymentInstruction instruction = instruction(Channel.EFT, "1250.00", "CAD");
    String key = instruction.idempotencyKey();

    publish(instruction);
    publish(instruction);

    assertThat(awaitStatuses(key, 2))
        .containsExactly(InstructionStatus.ROUTED, InstructionStatus.DUPLICATE);
    List<ConsumerRecord<String, String>> routed = TopicReader.byKey(Topics.ROUTED_EFT, key);
    assertThat(routed).hasSize(1);
    assertThat(parse(routed.get(0).value(), PaymentInstruction.class)).isEqualTo(instruction);
    assertThat(header(routed.get(0), EventHeaders.EVENT_TYPE)).isEqualTo("INSTRUCTION_ROUTED");
    assertThat(header(routed.get(0), EventHeaders.CORRELATION_ID))
        .isEqualTo(instruction.batchId().toString());
    assertThat(header(routed.get(0), "routingStatus")).isEqualTo("ROUTED");
    assertThat(statusEvents(key).get(1).detail()).isEqualTo("already ROUTED");
    assertThat(ledgerRow(key))
        .containsEntry("status", "ROUTED")
        .containsEntry("attempts", 1)
        .containsEntry("channel", "EFT");
  }

  @Test
  void statusEventsCarryTheStandardHeadersAndTheInstructionFields() {
    PaymentInstruction instruction = instruction(Channel.INTERAC, "500.00", "CAD");

    publish(instruction);
    awaitStatuses(instruction.idempotencyKey(), 1);

    ConsumerRecord<String, String> record =
        TopicReader.byKey(Topics.STATUS_EVENTS, instruction.idempotencyKey()).get(0);
    InstructionStatusEvent event = parse(record.value(), InstructionStatusEvent.class);
    assertThat(event.batchId()).isEqualTo(instruction.batchId());
    assertThat(event.channel()).isEqualTo(Channel.INTERAC);
    assertThat(event.status()).isEqualTo(InstructionStatus.ROUTED);
    assertThat(event.occurredAt()).isNotNull();
    assertThat(header(record, EventHeaders.EVENT_TYPE)).isEqualTo("INSTRUCTION_STATUS");
    assertThat(header(record, EventHeaders.CORRELATION_ID))
        .isEqualTo(instruction.batchId().toString());
    assertThat(header(record, EventHeaders.SCHEMA_VERSION)).isEqualTo("1");
    assertThat(header(record, EventHeaders.EVENT_ID)).hasSize(36);
    assertThat(header(record, EventHeaders.OCCURRED_AT)).isEqualTo(event.occurredAt().toString());
  }

  @Test
  void aWireOfOnePointFiveMillionEndsRequiresReview() {
    PaymentInstruction wire = instruction(Channel.WIRE, "1500000.00", "USD");
    String key = wire.idempotencyKey();

    publish(wire);

    assertThat(awaitStatuses(key, 1)).containsExactly(InstructionStatus.REQUIRES_REVIEW);
    List<ConsumerRecord<String, String>> routed = TopicReader.byKey(Topics.ROUTED_WIRE, key);
    assertThat(routed).hasSize(1);
    assertThat(header(routed.get(0), "routingStatus")).isEqualTo("REQUIRES_REVIEW");
    Map<String, Object> row = ledgerRow(key);
    assertThat(row).containsEntry("status", "REQUIRES_REVIEW").containsEntry("attempts", 1);
    assertThat(row.get("amount").toString()).isEqualTo("1500000.00");
  }

  @Test
  void aRowLeftInRoutingIsProcessedOnTheNextDelivery() {
    PaymentInstruction instruction = instruction(Channel.EFT, "75.10", "CAD");
    String key = instruction.idempotencyKey();
    insertRoutingRow(instruction);

    publish(instruction);

    assertThat(awaitStatuses(key, 1)).containsExactly(InstructionStatus.ROUTED);
    assertThat(TopicReader.byKey(Topics.ROUTED_EFT, key)).hasSize(1);
    assertThat(ledgerRow(key)).containsEntry("status", "ROUTED").containsEntry("attempts", 2);
  }

  @Test
  void aReplayPublishesReplayedBeforeRouted() {
    PaymentInstruction original = instruction(Channel.EFT, "310.00", "CAD");
    insertRoutingRow(original);

    publish(withReplayCount(original, 1));

    awaitStatuses(original.idempotencyKey(), 2);
    List<InstructionStatusEvent> events = statusEvents(original.idempotencyKey());
    assertThat(events)
        .extracting(InstructionStatusEvent::status)
        .containsExactly(InstructionStatus.REPLAYED, InstructionStatus.ROUTED);
    assertThat(events.get(0).detail()).isEqualTo("replay 1");
    assertThat(events.get(0).occurredAt()).isBeforeOrEqualTo(events.get(1).occurredAt());
  }

  private void insertRoutingRow(PaymentInstruction instruction) {
    Timestamp earlier = Timestamp.from(Instant.now().minusSeconds(60));
    jdbc.update(
        "INSERT INTO processed_instruction (idempotency_key, batch_id, channel, amount, status,"
            + " attempts, created_at, updated_at, version) VALUES (?, ?, ?, ?, 'ROUTING', 1, ?, ?,"
            + " 0)",
        instruction.idempotencyKey(),
        instruction.batchId().toString(),
        instruction.channel().name(),
        instruction.amount(),
        earlier,
        earlier);
  }

  private static String header(ConsumerRecord<String, String> record, String name) {
    return EventHeaders.lastValue(record.headers(), name).orElseThrow();
  }
}

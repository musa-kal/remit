package ca.musakaleem.remit.common;

import static org.assertj.core.api.Assertions.assertThat;

import ca.musakaleem.remit.common.event.BatchEvent;
import ca.musakaleem.remit.common.event.BatchStatus;
import ca.musakaleem.remit.common.event.Channel;
import ca.musakaleem.remit.common.event.InstructionRejected;
import ca.musakaleem.remit.common.event.InstructionStatus;
import ca.musakaleem.remit.common.event.InstructionStatusEvent;
import ca.musakaleem.remit.common.event.Party;
import ca.musakaleem.remit.common.event.PaymentInstruction;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RemitJsonTest {

  private static final UUID BATCH_ID = UUID.fromString("7f1c9a5e-2d3b-4c8e-9f10-1a2b3c4d5e6f");

  private final ObjectMapper mapper = RemitJson.objectMapper();

  @Test
  void writesDatesAsIsoStringsAndDecimalsPlain() throws Exception {
    PaymentInstruction instruction =
        new PaymentInstruction(
            "key",
            BATCH_ID,
            "ACME01",
            "ACME-0001",
            new Party("Jane Doe", "001", "00011", "1234567"),
            new Party("John Roe", "002", "00022", "7654321"),
            new BigDecimal("1000000.00"),
            "CAD",
            Channel.WIRE,
            LocalDate.of(2026, 10, 15),
            null,
            0);

    String raw = mapper.writeValueAsString(instruction);
    JsonNode json = mapper.readTree(raw);

    assertThat(json.get("valueDate").asText()).isEqualTo("2026-10-15");
    assertThat(raw).contains("\"amount\":1000000.00");
    assertThat(json.get("channel").asText()).isEqualTo("WIRE");
    assertThat(json.get("debtor").get("account").asText()).isEqualTo("1234567");
  }

  @Test
  void roundTripsEveryEventRecord() throws Exception {
    Instant at = Instant.parse("2026-10-15T13:30:00.123Z");
    List<Object> events =
        List.of(
            new PaymentInstruction(
                "k",
                BATCH_ID,
                "ACME01",
                "R1",
                new Party("A", "001", "00011", "1234567"),
                new Party("B", "002", "00022", "7654321"),
                new BigDecimal("10.50"),
                "USD",
                Channel.EFT,
                LocalDate.of(2026, 10, 15),
                "memo",
                2),
            new InstructionRejected(
                BATCH_ID, "ACME01", 7L, "R2", List.of("INVALID_AMOUNT"), "R2,***4567"),
            new BatchEvent(
                BATCH_ID,
                "ACME01",
                "PAY_ACME01_20261015093000.csv",
                "abc",
                BatchStatus.COMPLETED_WITH_ERRORS,
                20,
                15,
                5,
                new BigDecimal("12345.67"),
                at),
            new InstructionStatusEvent(
                "k", BATCH_ID, InstructionStatus.ROUTED, Channel.EFT, null, at));

    for (Object event : events) {
      String json = mapper.writeValueAsString(event);
      assertThat(mapper.readValue(json, event.getClass())).isEqualTo(event);
    }
  }

  @Test
  void writesInstantsAsIsoUtcStrings() throws Exception {
    InstructionStatusEvent event =
        new InstructionStatusEvent(
            "k",
            BATCH_ID,
            InstructionStatus.REQUIRES_REVIEW,
            Channel.WIRE,
            "over threshold",
            Instant.parse("2026-10-15T13:30:00Z"));

    JsonNode json = mapper.readTree(mapper.writeValueAsString(event));

    assertThat(json.get("occurredAt").asText()).isEqualTo("2026-10-15T13:30:00Z");
  }

  @Test
  void ignoresUnknownPropertiesFromNewerProducers() throws Exception {
    String json =
        "{\"name\":\"A\",\"institution\":\"001\",\"transit\":\"00011\","
            + "\"account\":\"1234567\",\"addedLater\":true}";

    assertThat(mapper.readValue(json, Party.class))
        .isEqualTo(new Party("A", "001", "00011", "1234567"));
  }

  @Test
  void configureReturnsTheSameMapper() {
    ObjectMapper existing = new ObjectMapper();
    assertThat(RemitJson.configure(existing)).isSameAs(existing);
  }
}

package ca.musakaleem.remit.intake.batch;

import static ca.musakaleem.remit.intake.support.PaymentFiles.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import ca.musakaleem.remit.common.event.Channel;
import ca.musakaleem.remit.common.event.PaymentInstruction;
import ca.musakaleem.remit.intake.config.RoutingRulesProperties;
import ca.musakaleem.remit.intake.config.RoutingRulesProperties.ChannelRule;
import ca.musakaleem.remit.intake.validation.InstructionValidationException;
import ca.musakaleem.remit.intake.validation.InstructionValidator;
import ca.musakaleem.remit.intake.validation.RawInstruction;
import ca.musakaleem.remit.intake.validation.ReasonCode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InstructionItemProcessorTest {

  private static final UUID BATCH_ID = UUID.fromString("7f1c9a5e-2d3b-4c8e-9f10-1a2b3c4d5e6f");
  // 14:00 UTC on 2026-10-15 is 10:00 in Toronto, so the business date is 2026-10-15.
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-10-15T14:00:00Z"), ZoneOffset.UTC);

  private final InstructionValidator validator =
      new InstructionValidator(
          new RoutingRulesProperties(
              Map.of(Channel.INTERAC, new ChannelRule(new BigDecimal("10000.00"), Set.of("CAD")))));
  private final InstructionLineMapper mapper = new InstructionLineMapper();

  @Test
  void turnsAValidRowIntoAnInstruction() {
    InstructionItemProcessor processor = processor("");

    PaymentInstruction instruction = processor.process(mapper.mapLine(row("A-1", "10.00"), 2));

    assertThat(instruction.batchId()).isEqualTo(BATCH_ID);
    assertThat(instruction.clientId()).isEqualTo("ACME01");
    assertThat(instruction.instructionRef()).isEqualTo("A-1");
  }

  @Test
  void rejectsARowOnADuplicateLine() {
    InstructionItemProcessor processor = processor("4, 9");

    InstructionValidationException e =
        catchThrowableOfType(
            InstructionValidationException.class,
            () -> processor.process(mapper.mapLine(row("A-1", "10.00"), 9)));

    assertThat(e.reasonCodes()).containsExactly(ReasonCode.DUPLICATE_INSTRUCTION_REF_IN_FILE);
    assertThat(processor.process(mapper.mapLine(row("A-1", "10.00"), 2))).isNotNull();
  }

  @Test
  void combinesFieldReasonsWithTheDuplicateReasonInCodeOrder() {
    InstructionItemProcessor processor = processor("3");

    InstructionValidationException e =
        catchThrowableOfType(
            InstructionValidationException.class,
            () -> processor.process(mapper.mapLine(row("A-1", "1234567", "0", "CAD", "EFT"), 3)));

    assertThat(e.reasonCodes())
        .containsExactly(ReasonCode.DUPLICATE_INSTRUCTION_REF_IN_FILE, ReasonCode.INVALID_AMOUNT);
    assertThat(e).hasMessageContaining("Line 3");
  }

  @Test
  void rejectsAMalformedRow() {
    RawInstruction malformed = mapper.mapLine("A-1,only,three", 5);

    assertThat(malformed.malformed()).isTrue();
    assertThat(malformed.lineNumber()).isEqualTo(5);
    InstructionValidationException e =
        catchThrowableOfType(
            InstructionValidationException.class, () -> processor(null).process(malformed));
    assertThat(e.reasonCodes()).containsExactly(ReasonCode.MALFORMED_ROW);
  }

  @Test
  void judgesValueDatesAgainstTheClock() {
    Clock nextDay = Clock.fixed(Instant.parse("2026-10-16T14:00:00Z"), ZoneOffset.UTC);
    InstructionItemProcessor processor =
        new InstructionItemProcessor(validator, nextDay, BATCH_ID, "ACME01", "");

    InstructionValidationException e =
        catchThrowableOfType(
            InstructionValidationException.class,
            () -> processor.process(mapper.mapLine(row("A-1", "10.00"), 2)));

    assertThat(e.reasonCodes()).containsExactly(ReasonCode.INVALID_VALUE_DATE);
  }

  private InstructionItemProcessor processor(String duplicateLines) {
    return new InstructionItemProcessor(validator, CLOCK, BATCH_ID, "ACME01", duplicateLines);
  }
}

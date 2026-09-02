package ca.musakaleem.remit.intake.validation;

import static org.assertj.core.api.Assertions.assertThat;

import ca.musakaleem.remit.common.IdempotencyKeys;
import ca.musakaleem.remit.common.event.Channel;
import ca.musakaleem.remit.common.event.Party;
import ca.musakaleem.remit.common.event.PaymentInstruction;
import ca.musakaleem.remit.intake.config.RoutingRulesProperties;
import ca.musakaleem.remit.intake.config.RoutingRulesProperties.ChannelRule;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class InstructionValidatorTest {

  static final LocalDate TODAY = LocalDate.of(2026, 10, 15);

  static final RoutingRulesProperties RULES =
      new RoutingRulesProperties(
          Map.of(
              Channel.INTERAC, new ChannelRule(new BigDecimal("10000.00"), Set.of("CAD")),
              Channel.EFT, new ChannelRule(new BigDecimal("25000000.00"), null)));

  private final InstructionValidator validator = new InstructionValidator(RULES);

  static List<String> validFields() {
    return new ArrayList<>(
        List.of(
            "ACME-0001",
            "Jane Doe",
            "001",
            "00011",
            "1234567",
            "John Roe",
            "002",
            "00022",
            "7654321",
            "100.00",
            "CAD",
            "EFT",
            "2026-10-15",
            "Invoice 1"));
  }

  static RawInstruction rowWith(Column column, String value) {
    List<String> fields = validFields();
    fields.set(column.ordinal(), value == null ? "" : value);
    return RawInstruction.parsed(2, String.join(",", fields), fields);
  }

  @Test
  void acceptsTheBaselineRow() {
    assertThat(validator.validate(rowWith(Column.AMOUNT, "100.00"), TODAY)).isEmpty();
  }

  @ParameterizedTest(name = "{0} = \"{1}\" is valid")
  @CsvSource(
      delimiter = '|',
      value = {
        "INSTRUCTION_REF|A",
        "INSTRUCTION_REF|abc-XYZ-0123456789",
        "INSTRUCTION_REF|12345678901234567890123456789012345",
        "DEBTOR_NAME|J",
        "DEBTOR_NAME|Jane Smith-Roe, Holdings Inc.",
        "CREDITOR_NAME|1234567890123456789012345678901234567890123456789012345678901234567890",
        "DEBTOR_INSTITUTION|000",
        "CREDITOR_INSTITUTION|999",
        "DEBTOR_TRANSIT|00000",
        "CREDITOR_TRANSIT|99999",
        "DEBTOR_ACCOUNT|1234567",
        "CREDITOR_ACCOUNT|123456789012",
        "AMOUNT|0.01",
        "AMOUNT|1",
        "AMOUNT|1.5",
        "AMOUNT|0001.50",
        "AMOUNT|9999.99",
        "CURRENCY|USD",
        "CHANNEL|WIRE",
        "VALUE_DATE|2026-11-14",
        "REMITTANCE_INFO|",
      })
  void acceptsValidFieldValues(Column column, String value) {
    assertThat(validator.validate(rowWith(column, value), TODAY)).isEmpty();
  }

  @ParameterizedTest(name = "{0} = \"{1}\" -> {2}")
  @CsvSource(
      delimiter = '|',
      value = {
        "INSTRUCTION_REF||INVALID_INSTRUCTION_REF",
        "INSTRUCTION_REF|123456789012345678901234567890123456|INVALID_INSTRUCTION_REF",
        "INSTRUCTION_REF|ACME_0001|INVALID_INSTRUCTION_REF",
        "INSTRUCTION_REF|ACME 0001|INVALID_INSTRUCTION_REF",
        "INSTRUCTION_REF|ACME/0001|INVALID_INSTRUCTION_REF",
        "DEBTOR_NAME||INVALID_NAME",
        "DEBTOR_NAME|'   '|INVALID_NAME",
        "CREDITOR_NAME|12345678901234567890123456789012345678901234567890123456789012345678901|INVALID_NAME",
        "DEBTOR_INSTITUTION|01|INVALID_INSTITUTION",
        "DEBTOR_INSTITUTION|0001|INVALID_INSTITUTION",
        "CREDITOR_INSTITUTION|0a1|INVALID_INSTITUTION",
        "CREDITOR_INSTITUTION||INVALID_INSTITUTION",
        "DEBTOR_TRANSIT|1234|INVALID_TRANSIT",
        "DEBTOR_TRANSIT|123456|INVALID_TRANSIT",
        "CREDITOR_TRANSIT|1234x|INVALID_TRANSIT",
        "DEBTOR_ACCOUNT|123456|INVALID_ACCOUNT",
        "DEBTOR_ACCOUNT|1234567890123|INVALID_ACCOUNT",
        "CREDITOR_ACCOUNT|12345-67|INVALID_ACCOUNT",
        "CREDITOR_ACCOUNT||INVALID_ACCOUNT",
        "AMOUNT||INVALID_AMOUNT",
        "AMOUNT|0|INVALID_AMOUNT",
        "AMOUNT|0.00|INVALID_AMOUNT",
        "AMOUNT|-5.00|INVALID_AMOUNT",
        "AMOUNT|+5.00|INVALID_AMOUNT",
        "AMOUNT|12.345|INVALID_AMOUNT",
        "AMOUNT|1e3|INVALID_AMOUNT",
        "AMOUNT|1,000.00|INVALID_AMOUNT",
        "AMOUNT|.50|INVALID_AMOUNT",
        "AMOUNT|5.|INVALID_AMOUNT",
        "AMOUNT|1000000000.00|INVALID_AMOUNT",
        "AMOUNT|abc|INVALID_AMOUNT",
        "CURRENCY|EUR|INVALID_CURRENCY",
        "CURRENCY|cad|INVALID_CURRENCY",
        "CURRENCY||INVALID_CURRENCY",
        "CHANNEL|ACH|INVALID_CHANNEL",
        "CHANNEL|eft|INVALID_CHANNEL",
        "CHANNEL||INVALID_CHANNEL",
        "VALUE_DATE|2026-10-14|INVALID_VALUE_DATE",
        "VALUE_DATE|2026-11-15|INVALID_VALUE_DATE",
        "VALUE_DATE|2026-02-30|INVALID_VALUE_DATE",
        "VALUE_DATE|15/10/2026|INVALID_VALUE_DATE",
        "VALUE_DATE|2026-10-5|INVALID_VALUE_DATE",
        "VALUE_DATE||INVALID_VALUE_DATE",
      })
  void rejectsInvalidFieldValues(Column column, String value, ReasonCode expected) {
    assertThat(validator.validate(rowWith(column, value), TODAY)).containsExactly(expected);
  }

  @Test
  void rejectsRemittanceOver140Chars() {
    assertThat(validator.validate(rowWith(Column.REMITTANCE_INFO, "x".repeat(140)), TODAY))
        .isEmpty();
    assertThat(validator.validate(rowWith(Column.REMITTANCE_INFO, "x".repeat(141)), TODAY))
        .containsExactly(ReasonCode.REMITTANCE_TOO_LONG);
  }

  @ParameterizedTest(name = "{0} {1} {2} -> valid")
  @CsvSource({
    "INTERAC, 10000.00, CAD",
    "INTERAC, 0.01, CAD",
    "EFT, 25000000.00, CAD",
    "EFT, 25000000.00, USD",
    "WIRE, 999999999.99, USD",
    "WIRE, 1000000.00, CAD",
  })
  void acceptsAmountsAndCurrenciesWithinChannelRules(String channel, String amount, String ccy) {
    RawInstruction row = row(channel, amount, ccy);

    assertThat(validator.validate(row, TODAY)).isEmpty();
  }

  @ParameterizedTest(name = "{0} {1} {2} -> {3}")
  @CsvSource({
    "INTERAC, 10000.01, CAD, CHANNEL_LIMIT_EXCEEDED",
    "INTERAC, 15000.00, CAD, CHANNEL_LIMIT_EXCEEDED",
    "INTERAC, 100.00, USD, CHANNEL_CURRENCY_NOT_ALLOWED",
    "EFT, 25000000.01, CAD, CHANNEL_LIMIT_EXCEEDED",
  })
  void rejectsChannelRuleViolations(String channel, String amount, String ccy, ReasonCode code) {
    assertThat(validator.validate(row(channel, amount, ccy), TODAY)).containsExactly(code);
  }

  @Test
  void reportsBothChannelRulesAtOnce() {
    assertThat(validator.validate(row("INTERAC", "20000.00", "USD"), TODAY))
        .containsExactly(
            ReasonCode.CHANNEL_LIMIT_EXCEEDED, ReasonCode.CHANNEL_CURRENCY_NOT_ALLOWED);
  }

  @Test
  void skipsChannelRulesWhenTheirInputsAreInvalid() {
    assertThat(validator.validate(row("INTERAC", "abc", "EUR"), TODAY))
        .containsExactly(ReasonCode.INVALID_AMOUNT, ReasonCode.INVALID_CURRENCY);
  }

  @Test
  void channelWithoutConfiguredRuleIsUnrestricted() {
    InstructionValidator noRules = new InstructionValidator(new RoutingRulesProperties(null));

    assertThat(noRules.validate(row("INTERAC", "999999999.99", "USD"), TODAY)).isEmpty();
  }

  @Test
  void reportsEveryFailedRuleOnceInDeclarationOrder() {
    List<String> fields = validFields();
    fields.set(Column.DEBTOR_ACCOUNT.ordinal(), "1");
    fields.set(Column.CREDITOR_ACCOUNT.ordinal(), "2");
    fields.set(Column.AMOUNT.ordinal(), "0");
    fields.set(Column.INSTRUCTION_REF.ordinal(), "");
    RawInstruction row = RawInstruction.parsed(5, "raw", fields);

    assertThat(validator.validate(row, TODAY))
        .containsExactly(
            ReasonCode.INVALID_INSTRUCTION_REF,
            ReasonCode.INVALID_ACCOUNT,
            ReasonCode.INVALID_AMOUNT);
  }

  @Test
  void malformedRowHasASingleReason() {
    assertThat(validator.validate(RawInstruction.malformed(3, "a,b"), TODAY))
        .containsExactly(ReasonCode.MALFORMED_ROW);
  }

  @Test
  void buildsTheInstructionEvent() {
    UUID batchId = UUID.randomUUID();
    RawInstruction row = row("WIRE", "1500000.5", "USD");

    PaymentInstruction instruction = validator.toInstruction(row, batchId, "ACME01");

    assertThat(instruction.idempotencyKey())
        .isEqualTo(IdempotencyKeys.forInstruction("ACME01", "ACME-0001"));
    assertThat(instruction.batchId()).isEqualTo(batchId);
    assertThat(instruction.clientId()).isEqualTo("ACME01");
    assertThat(instruction.instructionRef()).isEqualTo("ACME-0001");
    assertThat(instruction.debtor()).isEqualTo(new Party("Jane Doe", "001", "00011", "1234567"));
    assertThat(instruction.creditor()).isEqualTo(new Party("John Roe", "002", "00022", "7654321"));
    assertThat(instruction.amount()).isEqualTo(new BigDecimal("1500000.50"));
    assertThat(instruction.currency()).isEqualTo("USD");
    assertThat(instruction.channel()).isEqualTo(Channel.WIRE);
    assertThat(instruction.valueDate()).isEqualTo(TODAY);
    assertThat(instruction.remittanceInfo()).isEqualTo("Invoice 1");
    assertThat(instruction.replayCount()).isZero();
  }

  @Test
  void emptyRemittanceBecomesNull() {
    PaymentInstruction instruction =
        validator.toInstruction(rowWith(Column.REMITTANCE_INFO, ""), UUID.randomUUID(), "ACME01");

    assertThat(instruction.remittanceInfo()).isNull();
  }

  private static RawInstruction row(String channel, String amount, String currency) {
    List<String> fields = validFields();
    fields.set(Column.CHANNEL.ordinal(), channel);
    fields.set(Column.AMOUNT.ordinal(), amount);
    fields.set(Column.CURRENCY.ordinal(), currency);
    return RawInstruction.parsed(2, String.join(",", fields), fields);
  }
}

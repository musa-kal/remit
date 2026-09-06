package ca.musakaleem.remit.routing.rules;

import static ca.musakaleem.remit.routing.support.TestInstructions.instruction;
import static ca.musakaleem.remit.routing.support.TestInstructions.withKeyBatchAndAmount;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.musakaleem.remit.common.event.Channel;
import ca.musakaleem.remit.common.event.InstructionStatus;
import ca.musakaleem.remit.common.event.PaymentInstruction;
import ca.musakaleem.remit.routing.config.RoutingProperties;
import ca.musakaleem.remit.routing.config.RoutingProperties.ChannelRule;
import ca.musakaleem.remit.routing.error.NonRetryableRoutingException;
import ca.musakaleem.remit.routing.ledger.ProcessingStatus;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class RoutingRulesTest {

  /** The illustrative defaults from application.yml. */
  static final RoutingProperties DEFAULTS =
      new RoutingProperties(
          Map.of(
              Channel.INTERAC, new ChannelRule(new BigDecimal("10000.00"), Set.of("CAD")),
              Channel.EFT, new ChannelRule(new BigDecimal("25000000.00"), null),
              Channel.WIRE, new ChannelRule(null, null)),
          new BigDecimal("1000000.00"),
          false,
          Duration.ofSeconds(10));

  private final RoutingRules rules = new RoutingRules(DEFAULTS);

  @ParameterizedTest
  @CsvSource({
    "EFT, 1250.00, CAD, payments.routed.eft, ROUTED",
    "EFT, 25000000.00, USD, payments.routed.eft, ROUTED",
    "EFT, 2000000.00, CAD, payments.routed.eft, ROUTED",
    "INTERAC, 0.01, CAD, payments.routed.interac, ROUTED",
    "INTERAC, 10000.00, CAD, payments.routed.interac, ROUTED",
    "WIRE, 250000.00, USD, payments.routed.wire, ROUTED",
    "WIRE, 999999.99, CAD, payments.routed.wire, ROUTED",
    "WIRE, 1000000.00, CAD, payments.routed.wire, REQUIRES_REVIEW",
    "WIRE, 1000000, CAD, payments.routed.wire, REQUIRES_REVIEW",
    "WIRE, 1000000.0000, CAD, payments.routed.wire, REQUIRES_REVIEW",
    "WIRE, 1500000.00, USD, payments.routed.wire, REQUIRES_REVIEW",
    "WIRE, 999999999.99, USD, payments.routed.wire, REQUIRES_REVIEW"
  })
  void routesEachChannelToItsTopic(
      Channel channel,
      String amount,
      String currency,
      String topic,
      ProcessingStatus expectedOutcome) {
    RoutingDecision decision = rules.decide(instruction(channel, amount, currency));

    assertThat(decision.channel()).isEqualTo(channel);
    assertThat(decision.topic()).isEqualTo(topic);
    assertThat(decision.outcome()).isEqualTo(expectedOutcome);
  }

  @Test
  void theReviewThresholdIsInclusiveAndComparedByValueNotScale() {
    assertThat(rules.decide(instruction(Channel.WIRE, "999999.99", "CAD")).outcome())
        .isEqualTo(ProcessingStatus.ROUTED);
    assertThat(rules.decide(instruction(Channel.WIRE, "1000000.00", "CAD")).outcome())
        .isEqualTo(ProcessingStatus.REQUIRES_REVIEW);
    assertThat(rules.decide(instruction(Channel.WIRE, "1E+6", "CAD")).outcome())
        .as("equals() would say 1E+6 differs from 1000000.00; compareTo does not")
        .isEqualTo(ProcessingStatus.REQUIRES_REVIEW);
  }

  @Test
  void onlyWireIsSentToReview() {
    assertThat(rules.decide(instruction(Channel.EFT, "5000000.00", "CAD")).outcome())
        .isEqualTo(ProcessingStatus.ROUTED);
  }

  @Test
  void decisionMapsToTheMatchingStatusEvent() {
    assertThat(rules.decide(instruction(Channel.WIRE, "1.00", "CAD")).instructionStatus())
        .isEqualTo(InstructionStatus.ROUTED);
    assertThat(rules.decide(instruction(Channel.WIRE, "1000000.00", "CAD")).instructionStatus())
        .isEqualTo(InstructionStatus.REQUIRES_REVIEW);
  }

  @ParameterizedTest
  @CsvSource({
    "INTERAC, 100.00, USD, INTERAC does not accept currency USD",
    "INTERAC, 10000.01, CAD, INTERAC amount 10000.01 exceeds the limit of 10000.00",
    "EFT, 25000000.01, CAD, EFT amount 25000000.01 exceeds the limit of 25000000.00"
  })
  void aChannelRuleViolationIsNotRetryable(
      Channel channel, String amount, String currency, String message) {
    PaymentInstruction instruction = instruction(channel, amount, currency);

    assertThatThrownBy(() -> rules.decide(instruction))
        .isInstanceOf(NonRetryableRoutingException.class)
        .hasMessage(message);
  }

  @Test
  void aChannelWithoutConfiguredRulesAcceptsAnyAmountAndCurrency() {
    RoutingRules unrestricted =
        new RoutingRules(
            new RoutingProperties(
                Map.of(), new BigDecimal("1000000.00"), false, Duration.ofSeconds(1)));

    assertThat(unrestricted.decide(instruction(Channel.INTERAC, "50000.00", "USD")).outcome())
        .isEqualTo(ProcessingStatus.ROUTED);
  }

  @Test
  void aCompleteInstructionIsRoutable() {
    PaymentInstruction instruction = instruction(Channel.EFT, "1.00", "CAD");

    rules.requireRoutable(instruction);

    assertThat(instruction.idempotencyKey()).hasSize(64);
  }

  @Test
  void anEmptyRecordIsNotRoutable() {
    assertThatThrownBy(() -> rules.requireRoutable(null))
        .isInstanceOf(NonRetryableRoutingException.class)
        .hasMessage("Record has no instruction payload");
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(
      strings = {
        "abc",
        "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
        "gggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggg"
      })
  void theKeyMustBeSixtyFourLowerCaseHexDigits(String key) {
    PaymentInstruction valid = instruction(Channel.EFT, "1.00", "CAD");
    PaymentInstruction invalid = withKeyBatchAndAmount(valid, key, valid.batchId(), valid.amount());

    assertThatThrownBy(() -> rules.requireRoutable(invalid))
        .isInstanceOf(NonRetryableRoutingException.class)
        .hasMessageContaining("idempotencyKey");
  }

  @Test
  void batchIdAndAPositiveAmountAreRequired() {
    PaymentInstruction valid = instruction(Channel.EFT, "1.00", "CAD");
    String key = valid.idempotencyKey();
    PaymentInstruction noBatch = withKeyBatchAndAmount(valid, key, null, BigDecimal.ONE);
    PaymentInstruction noAmount = withKeyBatchAndAmount(valid, key, valid.batchId(), null);
    PaymentInstruction zero = withKeyBatchAndAmount(valid, key, valid.batchId(), BigDecimal.ZERO);

    assertThatThrownBy(() -> rules.requireRoutable(noBatch))
        .isInstanceOf(NonRetryableRoutingException.class)
        .hasMessage("batchId is required");
    assertThatThrownBy(() -> rules.requireRoutable(noAmount))
        .isInstanceOf(NonRetryableRoutingException.class)
        .hasMessage("amount is required");
    assertThatThrownBy(() -> rules.requireRoutable(zero))
        .isInstanceOf(NonRetryableRoutingException.class)
        .hasMessage("amount must be greater than zero");
  }

  @Test
  void channelAndCurrencyAreRequired() {
    PaymentInstruction noChannel = instruction(null, "1.00", "CAD");
    PaymentInstruction noCurrency = instruction(Channel.EFT, "1.00", null);

    assertThatThrownBy(() -> rules.requireRoutable(noChannel))
        .isInstanceOf(NonRetryableRoutingException.class)
        .hasMessage("channel is required");
    assertThatThrownBy(() -> rules.requireRoutable(noCurrency))
        .isInstanceOf(NonRetryableRoutingException.class)
        .hasMessage("currency is required");
  }

  @Test
  void aDecisionMustHaveAFinalOutcome() {
    assertThatThrownBy(
            () -> new RoutingDecision(Channel.EFT, "payments.routed.eft", ProcessingStatus.ROUTING))
        .isInstanceOf(IllegalArgumentException.class);
  }
}

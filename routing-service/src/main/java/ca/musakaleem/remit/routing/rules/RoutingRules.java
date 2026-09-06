package ca.musakaleem.remit.routing.rules;

import ca.musakaleem.remit.common.Topics;
import ca.musakaleem.remit.common.event.Channel;
import ca.musakaleem.remit.common.event.PaymentInstruction;
import ca.musakaleem.remit.routing.config.RoutingProperties;
import ca.musakaleem.remit.routing.config.RoutingProperties.ChannelRule;
import ca.musakaleem.remit.routing.error.NonRetryableRoutingException;
import ca.musakaleem.remit.routing.ledger.ProcessingStatus;
import java.math.BigDecimal;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Routing decision logic. intake-service already validated every instruction, but routing-service
 * also receives replays, so the channel rules of spec 3.3 are checked again here (defensively).
 */
@Component
public class RoutingRules {

  private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[0-9a-f]{64}");

  private final RoutingProperties properties;

  /**
   * Creates the rules.
   *
   * @param properties channel rules and the WIRE review threshold
   */
  public RoutingRules(RoutingProperties properties) {
    this.properties = properties;
  }

  /**
   * Checks the fields routing-service needs before it can claim the instruction key: without them
   * no ledger row can be written, so the record can only be dead-lettered.
   *
   * @param instruction consumed instruction, may be {@code null} for an empty record
   * @throws NonRetryableRoutingException when a required field is missing or malformed
   */
  public void requireRoutable(PaymentInstruction instruction) {
    if (instruction == null) {
      throw new NonRetryableRoutingException("Record has no instruction payload");
    }
    if (instruction.idempotencyKey() == null
        || !IDEMPOTENCY_KEY.matcher(instruction.idempotencyKey()).matches()) {
      throw new NonRetryableRoutingException("idempotencyKey must be 64 lower-case hex digits");
    }
    requirePresent(instruction.batchId(), "batchId");
    requirePresent(instruction.channel(), "channel");
    requirePresent(instruction.currency(), "currency");
    requirePresent(instruction.amount(), "amount");
    if (instruction.amount().signum() <= 0) {
      throw new NonRetryableRoutingException("amount must be greater than zero");
    }
  }

  /**
   * Applies the channel rules and the review threshold.
   *
   * @param instruction an instruction that passed {@link #requireRoutable}
   * @return the decision
   * @throws NonRetryableRoutingException when the instruction breaks a channel rule
   */
  public RoutingDecision decide(PaymentInstruction instruction) {
    Channel channel = instruction.channel();
    BigDecimal amount = instruction.amount();
    ChannelRule rule = properties.ruleFor(channel);

    if (!rule.currencies().isEmpty() && !rule.currencies().contains(instruction.currency())) {
      throw new NonRetryableRoutingException(
          "%s does not accept currency %s".formatted(channel, instruction.currency()));
    }
    if (rule.maxAmount() != null && amount.compareTo(rule.maxAmount()) > 0) {
      throw new NonRetryableRoutingException(
          "%s amount %s exceeds the limit of %s"
              .formatted(channel, amount.toPlainString(), rule.maxAmount().toPlainString()));
    }

    boolean needsReview =
        channel == Channel.WIRE && amount.compareTo(properties.reviewThreshold()) >= 0;
    ProcessingStatus outcome =
        needsReview ? ProcessingStatus.REQUIRES_REVIEW : ProcessingStatus.ROUTED;
    return new RoutingDecision(channel, Topics.routed(channel), outcome);
  }

  private static void requirePresent(Object value, String field) {
    if (value == null) {
      throw new NonRetryableRoutingException(field + " is required");
    }
  }
}

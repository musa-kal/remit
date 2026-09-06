package ca.musakaleem.remit.routing.rules;

import ca.musakaleem.remit.common.event.Channel;
import ca.musakaleem.remit.common.event.InstructionStatus;
import ca.musakaleem.remit.routing.ledger.ProcessingStatus;
import java.util.Objects;

/**
 * Where an instruction goes and with which outcome.
 *
 * @param channel channel the instruction is routed to
 * @param topic {@code payments.routed.<channel>} topic to publish to
 * @param outcome ROUTED or REQUIRES_REVIEW
 */
public record RoutingDecision(Channel channel, String topic, ProcessingStatus outcome) {

  /** Validates the decision. */
  public RoutingDecision {
    Objects.requireNonNull(channel, "channel");
    Objects.requireNonNull(topic, "topic");
    if (!Objects.requireNonNull(outcome, "outcome").isFinal()) {
      throw new IllegalArgumentException("A decision needs a final outcome, not " + outcome);
    }
  }

  /**
   * The status event that announces this decision.
   *
   * @return ROUTED or REQUIRES_REVIEW
   */
  public InstructionStatus instructionStatus() {
    return outcome == ProcessingStatus.REQUIRES_REVIEW
        ? InstructionStatus.REQUIRES_REVIEW
        : InstructionStatus.ROUTED;
  }
}

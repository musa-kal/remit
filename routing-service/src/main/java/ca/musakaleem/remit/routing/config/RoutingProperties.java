package ca.musakaleem.remit.routing.config;

import ca.musakaleem.remit.common.event.Channel;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Routing settings bound from {@code remit.routing}. The {@code rules} map has the same shape as
 * intake-service's {@code RoutingRulesProperties}, so one YAML block configures both services
 * (docs/decisions/0008-channel-rules-per-service.md). The limits are illustrative, not real scheme
 * limits.
 *
 * @param rules rule per channel; a channel without an entry has no extra rules
 * @param reviewThreshold WIRE amounts at or above this are routed as REQUIRES_REVIEW
 * @param chaosEnabled whether {@code remittanceInfo = FORCE_FAIL} forces a retryable failure
 *     (FR-RTE-04, demos and tests only)
 * @param publishTimeout how long to wait for Kafka to acknowledge a publish
 */
@Validated
@ConfigurationProperties("remit.routing")
public record RoutingProperties(
    Map<Channel, ChannelRule> rules,
    @DefaultValue("1000000.00") @NotNull @Positive BigDecimal reviewThreshold,
    @DefaultValue("false") boolean chaosEnabled,
    @DefaultValue("10s") @NotNull Duration publishTimeout) {

  /** Binds an absent map as empty. */
  public RoutingProperties {
    rules = rules == null ? Map.of() : Map.copyOf(rules);
  }

  /**
   * Returns the rule for a channel.
   *
   * @param channel the channel
   * @return the rule, or an unrestricted rule when none is configured
   */
  public ChannelRule ruleFor(Channel channel) {
    return rules.getOrDefault(channel, ChannelRule.UNRESTRICTED);
  }

  /**
   * Limits for one channel.
   *
   * @param maxAmount highest allowed amount (inclusive), or {@code null} for no maximum
   * @param currencies allowed currencies, or empty for any valid currency
   */
  public record ChannelRule(BigDecimal maxAmount, Set<String> currencies) {

    /** A rule that allows any amount and currency. */
    public static final ChannelRule UNRESTRICTED = new ChannelRule(null, Set.of());

    /** Binds an absent currency list as empty. */
    public ChannelRule {
      currencies = currencies == null ? Set.of() : Set.copyOf(currencies);
    }
  }
}

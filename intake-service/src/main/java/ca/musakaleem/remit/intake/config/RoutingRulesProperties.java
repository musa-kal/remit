package ca.musakaleem.remit.intake.config;

import ca.musakaleem.remit.common.event.Channel;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Channel-specific validation rules bound from {@code remit.routing.rules} (spec 3.3). The limits
 * are illustrative, not real scheme limits.
 *
 * @param rules rule per channel; a channel without an entry has no extra rules
 */
@ConfigurationProperties("remit.routing")
public record RoutingRulesProperties(Map<Channel, ChannelRule> rules) {

  /** Binds an absent map as empty. */
  public RoutingRulesProperties {
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

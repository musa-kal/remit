package ca.musakaleem.remit.routing.config;

import static org.assertj.core.api.Assertions.assertThat;

import ca.musakaleem.remit.common.event.Channel;
import ca.musakaleem.remit.routing.config.RoutingProperties.ChannelRule;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RoutingPropertiesTest {

  @Test
  void aChannelWithoutARuleIsUnrestricted() {
    RoutingProperties properties =
        new RoutingProperties(null, new BigDecimal("1000000.00"), false, Duration.ofSeconds(10));

    assertThat(properties.rules()).isEmpty();
    assertThat(properties.ruleFor(Channel.WIRE)).isEqualTo(ChannelRule.UNRESTRICTED);
    assertThat(ChannelRule.UNRESTRICTED.maxAmount()).isNull();
    assertThat(ChannelRule.UNRESTRICTED.currencies()).isEmpty();
  }

  @Test
  void returnsTheConfiguredRule() {
    ChannelRule interac = new ChannelRule(new BigDecimal("10000.00"), Set.of("CAD"));
    RoutingProperties properties =
        new RoutingProperties(
            Map.of(Channel.INTERAC, interac),
            new BigDecimal("1000000.00"),
            true,
            Duration.ofSeconds(10));

    assertThat(properties.ruleFor(Channel.INTERAC)).isEqualTo(interac);
    assertThat(properties.chaosEnabled()).isTrue();
  }

  @Test
  void anAbsentCurrencyListMeansAnyCurrency() {
    assertThat(new ChannelRule(BigDecimal.TEN, null).currencies()).isEmpty();
  }
}

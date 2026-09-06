package ca.musakaleem.remit.routing.rules;

import static ca.musakaleem.remit.routing.support.TestInstructions.instruction;
import static ca.musakaleem.remit.routing.support.TestInstructions.withRemittance;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.musakaleem.remit.common.event.Channel;
import ca.musakaleem.remit.common.event.PaymentInstruction;
import ca.musakaleem.remit.routing.config.RoutingProperties;
import ca.musakaleem.remit.routing.error.RoutingException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class ChaosHookTest {

  private static final PaymentInstruction FORCE_FAIL =
      withRemittance(instruction(Channel.EFT, "10.00", "CAD"), "FORCE_FAIL");

  @Test
  void enabledHookFailsAForceFailInstructionWithARetryableException() {
    ChaosHook hook = hook(true);

    assertThatThrownBy(() -> hook.apply(FORCE_FAIL))
        .isExactlyInstanceOf(RoutingException.class)
        .hasMessageContaining("FORCE_FAIL");
  }

  @Test
  void enabledHookFailsEveryAttempt() {
    ChaosHook hook = hook(true);

    for (int attempt = 1; attempt <= 3; attempt++) {
      assertThatThrownBy(() -> hook.apply(FORCE_FAIL)).isInstanceOf(RoutingException.class);
    }
  }

  @Test
  void disabledHookIgnoresForceFail() {
    assertThatCode(() -> hook(false).apply(FORCE_FAIL)).doesNotThrowAnyException();
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"Invoice 1001", "force_fail", "FORCE_FAIL ", " FORCE_FAIL"})
  void enabledHookIgnoresAnyOtherRemittance(String remittance) {
    PaymentInstruction instruction =
        withRemittance(instruction(Channel.EFT, "10.00", "CAD"), remittance);

    assertThatCode(() -> hook(true).apply(instruction)).doesNotThrowAnyException();
  }

  private static ChaosHook hook(boolean enabled) {
    return new ChaosHook(
        new RoutingProperties(
            Map.of(), new BigDecimal("1000000.00"), enabled, Duration.ofSeconds(1)));
  }
}

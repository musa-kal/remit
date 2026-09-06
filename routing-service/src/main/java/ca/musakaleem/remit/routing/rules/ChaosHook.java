package ca.musakaleem.remit.routing.rules;

import ca.musakaleem.remit.common.event.PaymentInstruction;
import ca.musakaleem.remit.routing.config.RoutingProperties;
import ca.musakaleem.remit.routing.error.RoutingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Deterministic failure hook for demos and tests (FR-RTE-04): when {@code
 * remit.routing.chaos-enabled} is true, an instruction whose {@code remittanceInfo} is exactly
 * {@value #FORCE_FAIL} fails with a retryable exception on every attempt, so it ends on the
 * dead-letter topic after the retries.
 */
@Component
public class ChaosHook {

  /** The remittance text that triggers the forced failure. */
  public static final String FORCE_FAIL = "FORCE_FAIL";

  private static final Logger log = LoggerFactory.getLogger(ChaosHook.class);

  private final boolean enabled;

  /**
   * Creates the hook.
   *
   * @param properties provides the {@code chaos-enabled} flag
   */
  public ChaosHook(RoutingProperties properties) {
    this.enabled = properties.chaosEnabled();
    if (enabled) {
      log.warn("Chaos hook enabled: remittanceInfo {} forces a routing failure", FORCE_FAIL);
    }
  }

  /**
   * Throws when the hook is enabled and the instruction asks for a forced failure.
   *
   * @param instruction the instruction being routed
   * @throws RoutingException the forced, retryable failure
   */
  public void apply(PaymentInstruction instruction) {
    if (enabled && FORCE_FAIL.equals(instruction.remittanceInfo())) {
      throw new RoutingException(
          "Forced failure: remittanceInfo is " + FORCE_FAIL + " and the chaos hook is enabled");
    }
  }
}

package ca.musakaleem.remit.routing.error;

/**
 * The instruction itself can never be routed (for example it breaks a channel rule), so retrying is
 * pointless. The error handler sends it straight to the dead-letter topic (FR-RTE-03).
 */
public class NonRetryableRoutingException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message why the instruction cannot be routed
   */
  public NonRetryableRoutingException(String message) {
    super(message);
  }
}

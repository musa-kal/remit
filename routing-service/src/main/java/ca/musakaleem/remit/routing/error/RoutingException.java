package ca.musakaleem.remit.routing.error;

/**
 * A routing attempt failed for a reason that may go away (Kafka or MySQL unavailable, a forced
 * chaos failure). The error handler retries it with back-off, then dead-letters it.
 */
public class RoutingException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message what failed
   */
  public RoutingException(String message) {
    super(message);
  }

  /**
   * Creates the exception with its cause.
   *
   * @param message what failed
   * @param cause underlying failure
   */
  public RoutingException(String message, Throwable cause) {
    super(message, cause);
  }
}

package ca.musakaleem.remit.intake.publish;

/** Kafka did not acknowledge a publish in time; the current step fails and can be restarted. */
public class EventPublishException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message what was being published
   * @param cause the underlying failure
   */
  public EventPublishException(String message, Throwable cause) {
    super(message, cause);
  }
}

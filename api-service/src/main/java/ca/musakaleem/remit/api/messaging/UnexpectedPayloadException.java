package ca.musakaleem.remit.api.messaging;

/**
 * A consumed record whose value is not the event its topic carries. Retrying cannot help, so the
 * error handler logs and skips it at once.
 */
public class UnexpectedPayloadException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message what was expected and found
   */
  public UnexpectedPayloadException(String message) {
    super(message);
  }
}

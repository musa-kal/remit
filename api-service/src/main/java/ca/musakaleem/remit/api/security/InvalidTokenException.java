package ca.musakaleem.remit.api.security;

/** A bearer token that is malformed, badly signed, expired or missing a required claim. */
public class InvalidTokenException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message why the token was refused, safe to return to the caller
   */
  public InvalidTokenException(String message) {
    super(message);
  }

  /**
   * Creates the exception with its cause.
   *
   * @param message why the token was refused, safe to return to the caller
   * @param cause the parser failure
   */
  public InvalidTokenException(String message, Throwable cause) {
    super(message, cause);
  }
}

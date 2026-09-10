package ca.musakaleem.remit.api.web;

/** A dependency (Kafka) did not answer in time; answered with 503 so the caller can retry. */
public class ServiceUnavailableException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param detail what is unavailable, returned to the caller
   * @param cause the underlying failure, logged but never returned
   */
  public ServiceUnavailableException(String detail, Throwable cause) {
    super(detail, cause);
  }
}

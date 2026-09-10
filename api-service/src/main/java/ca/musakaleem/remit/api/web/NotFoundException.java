package ca.musakaleem.remit.api.web;

/** The requested batch, instruction or dead letter does not exist; answered with 404. */
public class NotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param detail what was not found, returned to the caller
   */
  public NotFoundException(String detail) {
    super(detail);
  }
}

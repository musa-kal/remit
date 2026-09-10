package ca.musakaleem.remit.api.web;

/** The request is valid but the resource's state forbids it; answered with 409. */
public class ConflictException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param detail why the request conflicts, returned to the caller
   */
  public ConflictException(String detail) {
    super(detail);
  }
}

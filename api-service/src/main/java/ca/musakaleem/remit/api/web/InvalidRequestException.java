package ca.musakaleem.remit.api.web;

/**
 * A request parameter is well formed but not acceptable, for example a sort property that is not
 * allowed or a range whose start is after its end; answered with 400 and a field error.
 */
public class InvalidRequestException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String field;

  /**
   * Creates the exception.
   *
   * @param field the offending parameter
   * @param message what is wrong with it
   */
  public InvalidRequestException(String field, String message) {
    super(message);
    this.field = field;
  }

  /**
   * The offending parameter.
   *
   * @return parameter name
   */
  public String getField() {
    return field;
  }
}

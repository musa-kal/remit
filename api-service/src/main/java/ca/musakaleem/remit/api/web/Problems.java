package ca.musakaleem.remit.api.web;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * Builds the RFC 7807 bodies the API returns (FR-SEC-04): {@code type}, {@code title}, {@code
 * status}, {@code detail}, {@code instance} and, for validation failures, {@code errors}. They
 * never contain a stack trace or an exception class name.
 */
public final class Problems {

  /** Name of the extra property listing field errors. */
  public static final String ERRORS = "errors";

  private Problems() {}

  /**
   * A problem with the standard title of its status.
   *
   * @param status HTTP status
   * @param detail human readable explanation
   * @return the problem
   */
  public static ProblemDetail of(HttpStatus status, String detail) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setTitle(status.getReasonPhrase());
    return problem;
  }

  /**
   * A 400 problem listing the invalid fields.
   *
   * @param errors field errors, in request order
   * @return the problem
   */
  public static ProblemDetail validation(List<FieldError> errors) {
    ProblemDetail problem = of(HttpStatus.BAD_REQUEST, "The request has invalid fields");
    problem.setProperty(ERRORS, errors);
    return problem;
  }
}

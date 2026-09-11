package ca.musakaleem.remit.api.web;

import ca.musakaleem.remit.api.auth.LoginLockedException;
import ca.musakaleem.remit.api.security.ProblemAccessDeniedHandler;
import jakarta.validation.ConstraintViolationException;
import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.data.mapping.PropertyReferenceException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns every exception a controller throws into an RFC 7807 ProblemDetail (FR-SEC-04). Spring MVC
 * exceptions (unknown path, wrong method, unreadable body) get the base class's standard problems;
 * validation failures list their fields; anything unexpected becomes a 500 without internals.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

  private final Clock clock;

  /**
   * Creates the handler.
   *
   * @param clock clock for the Retry-After of a locked login
   */
  public ApiExceptionHandler(Clock clock) {
    this.clock = clock;
  }

  /**
   * 404 for an unknown batch, instruction or dead letter.
   *
   * @param e the exception
   * @return the problem
   */
  @ExceptionHandler(NotFoundException.class)
  public ProblemDetail notFound(NotFoundException e) {
    return Problems.of(HttpStatus.NOT_FOUND, e.getMessage());
  }

  /**
   * 409 when the resource's state forbids the request.
   *
   * @param e the exception
   * @return the problem
   */
  @ExceptionHandler(ConflictException.class)
  public ProblemDetail conflict(ConflictException e) {
    return Problems.of(HttpStatus.CONFLICT, e.getMessage());
  }

  /**
   * 400 for a parameter that is well formed but not acceptable.
   *
   * @param e the exception
   * @return the problem
   */
  @ExceptionHandler(InvalidRequestException.class)
  public ProblemDetail invalidRequest(InvalidRequestException e) {
    return Problems.validation(List.of(new FieldError(e.getField(), e.getMessage())));
  }

  /**
   * 400 for a sort property that does not exist.
   *
   * @param e the exception
   * @return the problem
   */
  @ExceptionHandler(PropertyReferenceException.class)
  public ProblemDetail unknownProperty(PropertyReferenceException e) {
    return Problems.validation(
        List.of(new FieldError("sort", "unknown property '" + e.getPropertyName() + "'")));
  }

  /**
   * 400 for a constraint violated outside a controller parameter.
   *
   * @param e the exception
   * @return the problem
   */
  @ExceptionHandler(ConstraintViolationException.class)
  public ProblemDetail constraintViolation(ConstraintViolationException e) {
    return Problems.validation(
        e.getConstraintViolations().stream()
            .map(v -> new FieldError(lastNode(v.getPropertyPath().toString()), v.getMessage()))
            .sorted(Comparator.comparing(FieldError::field))
            .toList());
  }

  /**
   * 401 for a failed login. The detail never says whether the user exists.
   *
   * @param e the exception
   * @return the problem
   */
  @ExceptionHandler(BadCredentialsException.class)
  public ProblemDetail badCredentials(BadCredentialsException e) {
    return Problems.of(HttpStatus.UNAUTHORIZED, "Invalid username or password");
  }

  /**
   * 401 for any other authentication failure inside a controller.
   *
   * @param e the exception
   * @return the problem
   */
  @ExceptionHandler(AuthenticationException.class)
  public ProblemDetail authentication(AuthenticationException e) {
    return Problems.of(HttpStatus.UNAUTHORIZED, "Authentication is required");
  }

  /**
   * 403 when {@code @PreAuthorize} refuses the caller's roles.
   *
   * @param e the exception
   * @return the problem
   */
  @ExceptionHandler(AccessDeniedException.class)
  public ProblemDetail accessDenied(AccessDeniedException e) {
    return Problems.of(HttpStatus.FORBIDDEN, ProblemAccessDeniedHandler.DETAIL);
  }

  /**
   * 429 with Retry-After while a username is locked (FR-SEC-06).
   *
   * @param e the exception
   * @return the problem
   */
  @ExceptionHandler(LoginLockedException.class)
  public ResponseEntity<ProblemDetail> locked(LoginLockedException e) {
    long seconds =
        Math.max(1, Duration.between(clock.instant(), e.getLockedUntil()).toSeconds() + 1);
    return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
        .header(HttpHeaders.RETRY_AFTER, Long.toString(seconds))
        .body(Problems.of(HttpStatus.TOO_MANY_REQUESTS, e.getMessage()));
  }

  /**
   * 503 when Kafka does not acknowledge a replay in time.
   *
   * @param e the exception
   * @return the problem
   */
  @ExceptionHandler(ServiceUnavailableException.class)
  public ProblemDetail unavailable(ServiceUnavailableException e) {
    log.error("{}", e.getMessage(), e);
    return Problems.of(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
  }

  /**
   * 500 for anything unexpected; the cause is logged, never returned.
   *
   * @param e the exception
   * @return the problem
   */
  @ExceptionHandler(Exception.class)
  public ProblemDetail unexpected(Exception e) {
    log.error("Unhandled exception", e);
    return Problems.of(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
  }

  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(
      MethodArgumentNotValidException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    List<FieldError> errors =
        ex.getBindingResult().getFieldErrors().stream()
            .map(e -> new FieldError(e.getField(), e.getDefaultMessage()))
            .toList();
    return ResponseEntity.badRequest().body(Problems.validation(errors));
  }

  @Override
  protected ResponseEntity<Object> handleHandlerMethodValidationException(
      HandlerMethodValidationException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    List<FieldError> errors =
        ex.getParameterValidationResults().stream()
            .flatMap(
                result ->
                    result.getResolvableErrors().stream()
                        .map(
                            error ->
                                new FieldError(
                                    result.getMethodParameter().getParameterName(),
                                    message(error))))
            .toList();
    return ResponseEntity.badRequest().body(Problems.validation(errors));
  }

  @Override
  protected ResponseEntity<Object> handleTypeMismatch(
      TypeMismatchException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
    String field = ex.getPropertyName() == null ? "request" : ex.getPropertyName();
    return ResponseEntity.badRequest()
        .body(
            Problems.validation(
                List.of(new FieldError(field, "invalid value '" + ex.getValue() + "'"))));
  }

  @Override
  protected ResponseEntity<Object> handleHttpMessageNotReadable(
      HttpMessageNotReadableException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    return ResponseEntity.badRequest()
        .body(Problems.of(HttpStatus.BAD_REQUEST, "The request body is missing or not valid JSON"));
  }

  private static String message(MessageSourceResolvable error) {
    return error.getDefaultMessage() == null ? "is invalid" : error.getDefaultMessage();
  }

  private static String lastNode(String path) {
    int dot = path.lastIndexOf('.');
    return dot < 0 ? path : path.substring(dot + 1);
  }
}

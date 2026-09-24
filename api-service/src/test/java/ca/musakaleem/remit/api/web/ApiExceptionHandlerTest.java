package ca.musakaleem.remit.api.web;

import static org.assertj.core.api.Assertions.assertThat;

import ca.musakaleem.remit.api.auth.LoginLockedException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.TypeMismatchException;
import org.springframework.core.MethodParameter;
import org.springframework.data.mapping.PropertyReferenceException;
import org.springframework.data.util.TypeInformation;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.context.request.ServletWebRequest;

class ApiExceptionHandlerTest {

  private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");

  private final ApiExceptionHandler handler =
      new ApiExceptionHandler(Clock.fixed(NOW, ZoneOffset.UTC));
  private final ObjectMapper json = Jackson2ObjectMapperBuilder.json().build();
  private final ServletWebRequest request =
      new ServletWebRequest(new MockHttpServletRequest("GET", "/api/v1/batches"));

  @Test
  void mapsDomainExceptionsToTheirStatus() {
    assertProblem(handler.notFound(new NotFoundException("Batch x not found")), 404, "Not Found")
        .isEqualTo("Batch x not found");
    assertProblem(handler.conflict(new ConflictException("Replay pending")), 409, "Conflict")
        .isEqualTo("Replay pending");
    assertProblem(
            handler.unavailable(new ServiceUnavailableException("Kafka down", new Exception())),
            503,
            "Service Unavailable")
        .isEqualTo("Kafka down");
  }

  @Test
  void mapsSecurityExceptionsWithoutRevealingWhy() {
    assertProblem(
            handler.badCredentials(new BadCredentialsException("user admin not found")),
            401,
            "Unauthorized")
        .isEqualTo("Invalid username or password");
    assertProblem(
            handler.authentication(new InsufficientAuthenticationException("x")),
            401,
            "Unauthorized")
        .isEqualTo("Authentication is required");
    assertProblem(handler.accessDenied(new AccessDeniedException("x")), 403, "Forbidden")
        .isEqualTo("Your roles do not allow this operation");
  }

  @Test
  void aLockedLoginIs429WithRetryAfter() {
    ResponseEntity<ProblemDetail> response =
        handler.locked(new LoginLockedException(NOW.plusSeconds(599)));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("600");
    assertThat(response.getBody().getDetail()).isEqualTo("Too many failed logins; try again later");
  }

  @Test
  void anUnexpectedExceptionIs500WithoutInternals() throws Exception {
    ProblemDetail problem =
        handler.unexpected(new IllegalStateException("SELECT * FROM secret_table failed"));

    String body = json.writeValueAsString(problem);
    assertThat(problem.getStatus()).isEqualTo(500);
    assertThat(body)
        .contains("An unexpected error occurred")
        .doesNotContain("secret_table")
        .doesNotContain("IllegalStateException")
        .doesNotContain("stack");
  }

  @Test
  void invalidParametersBecome400WithFieldErrors() throws Exception {
    JsonNode badSort = body(handler.unknownProperty(propertyReference("bogus")));
    JsonNode badRange =
        body(handler.invalidRequest(new InvalidRequestException("from", "must be before 'to'")));

    assertThat(badSort.path("status").asInt()).isEqualTo(400);
    assertThat(badSort.path("errors").get(0).path("field").asText()).isEqualTo("sort");
    assertThat(badSort.path("errors").get(0).path("message").asText())
        .isEqualTo("unknown property 'bogus'");
    assertThat(badRange.path("errors").get(0).path("field").asText()).isEqualTo("from");
  }

  @Test
  void aBodyFieldErrorListsTheField() throws Exception {
    BeanPropertyBindingResult result = new BeanPropertyBindingResult(new Object(), "login");
    result.addError(
        new org.springframework.validation.FieldError("login", "username", "must not be blank"));
    MethodParameter parameter =
        new MethodParameter(
            ApiExceptionHandlerTest.class.getDeclaredMethod("sample", String.class), 0);

    ResponseEntity<Object> response =
        handler.handleMethodArgumentNotValid(
            new MethodArgumentNotValidException(parameter, result),
            new HttpHeaders(),
            HttpStatus.BAD_REQUEST,
            request);

    JsonNode body = json.valueToTree(response.getBody());
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(body.path("errors").get(0).path("field").asText()).isEqualTo("username");
    assertThat(body.path("errors").get(0).path("message").asText()).isEqualTo("must not be blank");
  }

  @Test
  void aTypeMismatchNamesTheParameterAndValue() {
    TypeMismatchException mismatch = new TypeMismatchException("BOGUS", Enum.class);
    mismatch.initPropertyName("status");

    ResponseEntity<Object> response =
        handler.handleTypeMismatch(mismatch, new HttpHeaders(), HttpStatus.BAD_REQUEST, request);

    JsonNode body = json.valueToTree(response.getBody());
    assertThat(body.path("errors").get(0).path("field").asText()).isEqualTo("status");
    assertThat(body.path("errors").get(0).path("message").asText())
        .isEqualTo("invalid value 'BOGUS'");
  }

  @Test
  void anUnreadableBodyDoesNotEchoTheParserMessage() {
    ResponseEntity<Object> response =
        handler.handleHttpMessageNotReadable(
            new HttpMessageNotReadableException(
                "JSON parse error at line 1", new MockHttpInputMessage(new byte[0])),
            new HttpHeaders(),
            HttpStatus.BAD_REQUEST,
            request);

    ProblemDetail problem = (ProblemDetail) response.getBody();
    assertThat(problem.getStatus()).isEqualTo(400);
    assertThat(problem.getDetail()).isEqualTo("The request body is missing or not valid JSON");
  }

  @Test
  void validationProblemsCarryAnErrorsProperty() {
    ProblemDetail problem = Problems.validation(List.of(new FieldError("size", "too big")));

    assertThat(problem.getTitle()).isEqualTo("Bad Request");
    assertThat(problem.getProperties()).containsKey(Problems.ERRORS);
  }

  @SuppressWarnings("unused")
  private void sample(String body) {
    // Never called: only its signature is used, as the MethodParameter of a validation failure.
  }

  private static PropertyReferenceException propertyReference(String name) {
    return new PropertyReferenceException(name, TypeInformation.of(Object.class), List.of());
  }

  private JsonNode body(ProblemDetail problem) {
    return json.valueToTree(problem);
  }

  private static org.assertj.core.api.AbstractStringAssert<?> assertProblem(
      ProblemDetail problem, int status, String title) {
    assertThat(problem.getStatus()).isEqualTo(status);
    assertThat(problem.getTitle()).isEqualTo(title);
    return assertThat(problem.getDetail());
  }
}

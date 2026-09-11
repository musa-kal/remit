package ca.musakaleem.remit.api.security;

import ca.musakaleem.remit.api.web.Problems;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/**
 * Answers 401 with a ProblemDetail when a request has no valid bearer token (FR-SEC-04), and a
 * {@code WWW-Authenticate: Bearer} header as RFC 6750 asks.
 */
@Component
public class ProblemAuthenticationEntryPoint implements AuthenticationEntryPoint {

  /** Detail when no token was sent. */
  public static final String MISSING_TOKEN = "Authentication is required: send a bearer token";

  private final ProblemResponseWriter writer;

  /**
   * Creates the entry point.
   *
   * @param writer ProblemDetail writer
   */
  public ProblemAuthenticationEntryPoint(ProblemResponseWriter writer) {
    this.writer = writer;
  }

  @Override
  public void commence(
      HttpServletRequest request,
      HttpServletResponse response,
      AuthenticationException authException)
      throws IOException {
    boolean invalidToken = authException.getCause() instanceof InvalidTokenException;
    String detail = invalidToken ? authException.getMessage() : MISSING_TOKEN;
    response.setHeader(
        HttpHeaders.WWW_AUTHENTICATE, invalidToken ? "Bearer error=\"invalid_token\"" : "Bearer");
    writer.write(request, response, Problems.of(HttpStatus.UNAUTHORIZED, detail));
  }
}

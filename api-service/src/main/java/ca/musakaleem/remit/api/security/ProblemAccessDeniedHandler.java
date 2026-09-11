package ca.musakaleem.remit.api.security;

import ca.musakaleem.remit.api.web.Problems;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/** Answers 403 with a ProblemDetail when an authenticated caller lacks the required role. */
@Component
public class ProblemAccessDeniedHandler implements AccessDeniedHandler {

  /** Detail of every 403. */
  public static final String DETAIL = "Your roles do not allow this operation";

  private final ProblemResponseWriter writer;

  /**
   * Creates the handler.
   *
   * @param writer ProblemDetail writer
   */
  public ProblemAccessDeniedHandler(ProblemResponseWriter writer) {
    this.writer = writer;
  }

  @Override
  public void handle(
      HttpServletRequest request,
      HttpServletResponse response,
      AccessDeniedException accessDeniedException)
      throws IOException {
    writer.write(request, response, Problems.of(HttpStatus.FORBIDDEN, DETAIL));
  }
}

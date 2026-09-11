package ca.musakaleem.remit.api.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

/**
 * Writes a ProblemDetail from inside the security filter chain, where no controller advice runs, in
 * the same shape as {@link ca.musakaleem.remit.api.web.ApiExceptionHandler}.
 */
@Component
public class ProblemResponseWriter {

  private final ObjectMapper objectMapper;

  /**
   * Creates the writer.
   *
   * @param objectMapper Spring's mapper, which knows how to serialize ProblemDetail
   */
  public ProblemResponseWriter(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  /**
   * Writes the problem as {@code application/problem+json} with its status.
   *
   * @param request the request, whose path becomes {@code instance}
   * @param response the response to write
   * @param problem the problem
   * @throws IOException when the response cannot be written
   */
  public void write(HttpServletRequest request, HttpServletResponse response, ProblemDetail problem)
      throws IOException {
    if (problem.getInstance() == null) {
      problem.setInstance(URI.create(request.getRequestURI()));
    }
    response.setStatus(problem.getStatus());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    objectMapper.writeValue(response.getOutputStream(), problem);
  }
}

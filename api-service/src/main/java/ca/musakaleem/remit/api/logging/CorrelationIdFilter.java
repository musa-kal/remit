package ca.musakaleem.remit.api.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Puts a correlation id in the MDC for every HTTP request (NFR-01), so the log lines of one call
 * can be found together. A well formed {@code X-Correlation-Id} request header is reused; otherwise
 * a new UUID is generated. The id is echoed in the response header.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

  /** Request and response header carrying the correlation id. */
  public static final String HEADER = "X-Correlation-Id";

  private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._-]{1,64}");

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String supplied = request.getHeader(HEADER);
    String correlationId =
        supplied != null && SAFE.matcher(supplied).matches()
            ? supplied
            : UUID.randomUUID().toString();
    MDC.put(MdcKeys.CORRELATION_ID, correlationId);
    response.setHeader(HEADER, correlationId);
    try {
      chain.doFilter(request, response);
    } finally {
      MDC.remove(MdcKeys.CORRELATION_ID);
    }
  }
}

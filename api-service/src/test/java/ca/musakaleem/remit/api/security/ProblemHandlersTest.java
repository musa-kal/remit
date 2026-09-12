package ca.musakaleem.remit.api.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import ca.musakaleem.remit.api.support.MutableClock;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class ProblemHandlersTest {

  private final ObjectMapper json = Jackson2ObjectMapperBuilder.json().build();
  private final ProblemResponseWriter writer = new ProblemResponseWriter(json);
  private final ProblemAuthenticationEntryPoint entryPoint =
      new ProblemAuthenticationEntryPoint(writer);
  private final MutableClock clock = new MutableClock(Instant.parse("2026-10-15T12:00:00Z"));
  private final JwtService jwt =
      new JwtService(new JwtProperties("y".repeat(40), Duration.ofMinutes(15)), clock);
  private final JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwt, entryPoint);

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void entryPointWritesA401ProblemWithABearerChallenge() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();

    entryPoint.commence(
        request("/api/v1/batches"), response, new InsufficientAuthenticationException("x"));

    JsonNode body = json.readTree(response.getContentAsString());
    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(response.getContentType()).isEqualTo("application/problem+json");
    assertThat(response.getHeader("WWW-Authenticate")).isEqualTo("Bearer");
    assertThat(body.path("title").asText()).isEqualTo("Unauthorized");
    assertThat(body.path("detail").asText())
        .isEqualTo(ProblemAuthenticationEntryPoint.MISSING_TOKEN);
    assertThat(body.path("instance").asText()).isEqualTo("/api/v1/batches");
  }

  @Test
  void accessDeniedHandlerWritesA403Problem() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();

    new ProblemAccessDeniedHandler(writer)
        .handle(request("/api/v1/audit-events"), response, new AccessDeniedException("x"));

    JsonNode body = json.readTree(response.getContentAsString());
    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(body.path("status").asInt()).isEqualTo(403);
    assertThat(body.path("detail").asText()).isEqualTo(ProblemAccessDeniedHandler.DETAIL);
  }

  @Test
  void filterAuthenticatesAValidTokenWithRoleAuthorities() throws Exception {
    MockHttpServletRequest request = request("/api/v1/batches");
    request.addHeader(
        "Authorization", "Bearer " + jwt.issue("admin", List.of("OPERATOR", "ADMIN")).token());
    Authentication[] seen = new Authentication[1];

    filter.doFilter(
        request,
        new MockHttpServletResponse(),
        (req, res) -> seen[0] = SecurityContextHolder.getContext().getAuthentication());

    assertThat(seen[0].getName()).isEqualTo("admin");
    assertThat(seen[0].getAuthorities())
        .extracting(GrantedAuthority::getAuthority)
        .containsExactly("ROLE_ADMIN", "ROLE_OPERATOR");
  }

  @Test
  void filterAnswers401ForAnExpiredTokenWithoutCallingTheChain() throws Exception {
    String token = jwt.issue("operator", List.of("OPERATOR")).token();
    clock.advance(Duration.ofMinutes(16));
    MockHttpServletRequest request = request("/api/v1/batches");
    request.addHeader("Authorization", "Bearer " + token);
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);

    filter.doFilter(request, response, chain);

    verify(chain, never()).doFilter(request, response);
    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(response.getHeader("WWW-Authenticate")).isEqualTo("Bearer error=\"invalid_token\"");
    assertThat(json.readTree(response.getContentAsString()).path("detail").asText())
        .isEqualTo("The access token has expired");
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
  }

  @Test
  void filterPassesRequestsWithoutABearerTokenThrough() throws Exception {
    MockHttpServletRequest basic = request("/api/v1/batches");
    basic.addHeader("Authorization", "Basic dXNlcjpwYXNz");
    FilterChain chain = mock(FilterChain.class);
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(basic, response, chain);

    verify(chain).doFilter(basic, response);
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
  }

  @Test
  void filterIgnoresTokensOnTheLoginEndpoint() throws Exception {
    MockHttpServletRequest login = request(SecurityConfig.LOGIN_PATH);
    login.addHeader("Authorization", "Bearer stale");
    FilterChain chain = mock(FilterChain.class);
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(login, response, chain);

    verify(chain).doFilter(login, response);
    assertThat(response.getStatus()).isEqualTo(200);
  }

  private static MockHttpServletRequest request(String path) {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
    request.setServletPath(path);
    return request;
  }
}

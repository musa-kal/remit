package ca.musakaleem.remit.api.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates a request from its {@code Authorization: Bearer <token>} header. A valid token
 * becomes an authentication whose principal is the username and whose authorities are {@code
 * ROLE_<role>}. An invalid or expired token is answered with 401 at once; a request without a token
 * continues anonymously and is refused later if the endpoint is protected.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

  private static final String BEARER_PREFIX = "Bearer ";

  private final JwtService jwtService;
  private final AuthenticationEntryPoint entryPoint;

  /**
   * Creates the filter.
   *
   * @param jwtService token verifier
   * @param entryPoint writes the 401 for an invalid token
   */
  public JwtAuthenticationFilter(JwtService jwtService, AuthenticationEntryPoint entryPoint) {
    this.jwtService = jwtService;
    this.entryPoint = entryPoint;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    // A stale token sent along with a login must not stop the login.
    return SecurityConfig.LOGIN_PATH.equals(request.getServletPath());
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String header = request.getHeader(HttpHeaders.AUTHORIZATION);
    if (header == null
        || !header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
      chain.doFilter(request, response);
      return;
    }
    try {
      TokenClaims claims = jwtService.parse(header.substring(BEARER_PREFIX.length()).trim());
      List<GrantedAuthority> authorities =
          claims.roles().stream()
              .sorted()
              .<GrantedAuthority>map(role -> new SimpleGrantedAuthority("ROLE_" + role))
              .toList();
      UsernamePasswordAuthenticationToken authentication =
          UsernamePasswordAuthenticationToken.authenticated(claims.username(), null, authorities);
      authentication.setDetails(claims);
      SecurityContext context = SecurityContextHolder.createEmptyContext();
      context.setAuthentication(authentication);
      SecurityContextHolder.setContext(context);
    } catch (InvalidTokenException e) {
      SecurityContextHolder.clearContext();
      entryPoint.commence(request, response, new BadCredentialsException(e.getMessage(), e));
      return;
    }
    chain.doFilter(request, response);
  }
}

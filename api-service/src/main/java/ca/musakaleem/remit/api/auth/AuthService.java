package ca.musakaleem.remit.api.auth;

import ca.musakaleem.remit.api.audit.AuditEntityType;
import ca.musakaleem.remit.api.audit.AuditEventType;
import ca.musakaleem.remit.api.audit.AuditWriter;
import ca.musakaleem.remit.api.security.IssuedToken;
import ca.musakaleem.remit.api.security.JwtService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;

/**
 * Checks a username and password and issues an access token. Locked usernames are refused before
 * the password is checked (FR-SEC-06). Every attempt writes a LOGIN_SUCCESS or LOGIN_FAILURE audit
 * document; a failure never tells the caller whether the user exists.
 */
@Service
public class AuthService {

  private static final Logger log = LoggerFactory.getLogger(AuthService.class);
  private static final String ROLE_PREFIX = "ROLE_";

  private final AuthenticationManager authenticationManager;
  private final LoginAttemptService attempts;
  private final JwtService jwtService;
  private final AuditWriter audit;

  /**
   * Creates the service.
   *
   * @param authenticationManager checks the password against the BCrypt hash
   * @param attempts failed login counter
   * @param jwtService token issuer
   * @param audit audit trail
   */
  public AuthService(
      AuthenticationManager authenticationManager,
      LoginAttemptService attempts,
      JwtService jwtService,
      AuditWriter audit) {
    this.authenticationManager = authenticationManager;
    this.attempts = attempts;
    this.jwtService = jwtService;
    this.audit = audit;
  }

  /**
   * Logs a user in.
   *
   * @param request username and password
   * @return the access token
   * @throws LoginLockedException when the username is locked
   * @throws BadCredentialsException when the username or password is wrong or the user is disabled
   */
  public TokenResponse login(LoginRequest request) {
    String username = request.username().trim();
    Optional<Instant> lockedUntil = attempts.lockedUntil(username);
    if (lockedUntil.isPresent()) {
      recordFailure(username, "LOCKED");
      throw new LoginLockedException(lockedUntil.get());
    }

    Authentication authentication;
    try {
      authentication =
          authenticationManager.authenticate(
              UsernamePasswordAuthenticationToken.unauthenticated(username, request.password()));
    } catch (AuthenticationException e) {
      boolean nowLocked = attempts.recordFailure(username);
      recordFailure(username, e instanceof DisabledException ? "DISABLED" : "BAD_CREDENTIALS");
      if (nowLocked) {
        log.warn("Username {} locked after repeated failed logins", username);
      }
      throw new BadCredentialsException("Invalid username or password");
    }

    attempts.recordSuccess(username);
    List<String> roles =
        authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .filter(a -> a.startsWith(ROLE_PREFIX))
            .map(a -> a.substring(ROLE_PREFIX.length()))
            .sorted()
            .toList();
    IssuedToken token = jwtService.issue(authentication.getName(), roles);
    audit.recordUserAction(
        authentication.getName(),
        AuditEventType.LOGIN_SUCCESS,
        AuditEntityType.USER,
        authentication.getName(),
        null,
        Map.of("roles", roles));
    return new TokenResponse(token.token(), TokenResponse.BEARER, token.expiresInSeconds());
  }

  private void recordFailure(String username, String reason) {
    audit.recordUserAction(
        username,
        AuditEventType.LOGIN_FAILURE,
        AuditEntityType.USER,
        username,
        null,
        Map.of("reason", reason));
  }
}

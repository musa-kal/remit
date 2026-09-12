package ca.musakaleem.remit.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ca.musakaleem.remit.api.audit.AuditEntityType;
import ca.musakaleem.remit.api.audit.AuditEventType;
import ca.musakaleem.remit.api.audit.AuditWriter;
import ca.musakaleem.remit.api.security.JwtProperties;
import ca.musakaleem.remit.api.security.JwtService;
import ca.musakaleem.remit.api.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;

class AuthServiceTest {

  private final MutableClock clock = new MutableClock(Instant.parse("2026-10-15T12:00:00Z"));
  private final AuthenticationManager manager = mock(AuthenticationManager.class);
  private final LoginAttemptService attempts =
      new LoginAttemptService(
          new LoginProperties(5, Duration.ofMinutes(10), Duration.ofMinutes(10)), clock);
  private final JwtService jwt =
      new JwtService(new JwtProperties("x".repeat(40), Duration.ofMinutes(15)), clock);
  private final AuditWriter audit = mock(AuditWriter.class);
  private final AuthService service = new AuthService(manager, attempts, jwt, audit);

  @Test
  void aValidLoginReturnsABearerTokenWithTheUsersRolesAndIsAudited() {
    when(manager.authenticate(any()))
        .thenReturn(
            UsernamePasswordAuthenticationToken.authenticated(
                "admin", null, AuthorityUtils.createAuthorityList("ROLE_OPERATOR", "ROLE_ADMIN")));

    TokenResponse response = service.login(new LoginRequest(" admin ", "secret"));

    assertThat(response.tokenType()).isEqualTo("Bearer");
    assertThat(response.expiresIn()).isEqualTo(900);
    assertThat(jwt.parse(response.accessToken()).roles())
        .containsExactlyInAnyOrder("ADMIN", "OPERATOR");
    verify(audit)
        .recordUserAction(
            "admin",
            AuditEventType.LOGIN_SUCCESS,
            AuditEntityType.USER,
            "admin",
            null,
            Map.of("roles", List.of("ADMIN", "OPERATOR")));
  }

  @Test
  void aWrongPasswordIsAGenericFailureAndIsAudited() {
    when(manager.authenticate(any())).thenThrow(new BadCredentialsException("Bad credentials"));

    assertThatThrownBy(() -> service.login(new LoginRequest("operator", "wrong")))
        .isInstanceOf(BadCredentialsException.class)
        .hasMessage("Invalid username or password");
    verify(audit)
        .recordUserAction(
            "operator",
            AuditEventType.LOGIN_FAILURE,
            AuditEntityType.USER,
            "operator",
            null,
            Map.of("reason", "BAD_CREDENTIALS"));
  }

  @Test
  void aDisabledAccountLooksLikeAWrongPasswordToTheCaller() {
    when(manager.authenticate(any())).thenThrow(new DisabledException("disabled"));

    assertThatThrownBy(() -> service.login(new LoginRequest("operator", "right")))
        .isInstanceOf(BadCredentialsException.class)
        .hasMessage("Invalid username or password");
    verify(audit)
        .recordUserAction(
            eq("operator"),
            eq(AuditEventType.LOGIN_FAILURE),
            eq(AuditEntityType.USER),
            eq("operator"),
            eq(null),
            eq(Map.of("reason", "DISABLED")));
  }

  @Test
  void afterFiveFailuresEvenTheRightPasswordIsRefusedWithoutCheckingIt() {
    when(manager.authenticate(any())).thenThrow(new BadCredentialsException("Bad credentials"));
    for (int i = 0; i < 5; i++) {
      assertThatThrownBy(() -> service.login(new LoginRequest("operator", "wrong")))
          .isInstanceOf(BadCredentialsException.class);
    }

    assertThatThrownBy(() -> service.login(new LoginRequest("operator", "right")))
        .isInstanceOf(LoginLockedException.class)
        .satisfies(
            e ->
                assertThat(((LoginLockedException) e).getLockedUntil())
                    .isEqualTo(clock.instant().plus(Duration.ofMinutes(10))));
    verify(audit)
        .recordUserAction(
            "operator",
            AuditEventType.LOGIN_FAILURE,
            AuditEntityType.USER,
            "operator",
            null,
            Map.of("reason", "LOCKED"));
  }

  @Test
  void aSuccessfulLoginResetsTheFailureCount() {
    when(manager.authenticate(any()))
        .thenThrow(new BadCredentialsException("Bad credentials"))
        .thenThrow(new BadCredentialsException("Bad credentials"))
        .thenThrow(new BadCredentialsException("Bad credentials"))
        .thenThrow(new BadCredentialsException("Bad credentials"))
        .thenReturn(
            UsernamePasswordAuthenticationToken.authenticated(
                "operator", null, AuthorityUtils.createAuthorityList("ROLE_OPERATOR")));
    for (int i = 0; i < 4; i++) {
      assertThatThrownBy(() -> service.login(new LoginRequest("operator", "wrong")))
          .isInstanceOf(BadCredentialsException.class);
    }

    service.login(new LoginRequest("operator", "right"));

    assertThat(attempts.lockedUntil("operator")).isEmpty();
    assertThat(attempts.recordFailure("operator")).isFalse();
    verify(audit, never())
        .recordUserAction(
            any(),
            eq(AuditEventType.LOGIN_FAILURE),
            any(),
            any(),
            any(),
            eq(Map.of("reason", "LOCKED")));
  }

  @Test
  void theRequestNeverPrintsThePassword() {
    assertThat(new LoginRequest("operator", "hunter2").toString())
        .contains("operator")
        .doesNotContain("hunter2");
  }
}

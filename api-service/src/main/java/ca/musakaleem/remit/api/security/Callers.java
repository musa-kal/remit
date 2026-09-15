package ca.musakaleem.remit.api.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

/** Questions about the authenticated caller that controllers need beyond {@code @PreAuthorize}. */
public final class Callers {

  /** Authority of the ADMIN role. */
  public static final String ROLE_ADMIN = "ROLE_ADMIN";

  private Callers() {}

  /**
   * Whether the caller holds ADMIN, which unmasks account numbers on the instruction detail
   * (FR-SEC-05).
   *
   * @param authentication the caller, may be {@code null}
   * @return true for an administrator
   */
  public static boolean isAdmin(Authentication authentication) {
    return authentication != null
        && authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .anyMatch(ROLE_ADMIN::equals);
  }
}

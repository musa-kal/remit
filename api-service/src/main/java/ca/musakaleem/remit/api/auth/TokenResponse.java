package ca.musakaleem.remit.api.auth;

/**
 * Body of a successful login.
 *
 * @param accessToken HS256 JWT to send as {@code Authorization: Bearer <token>}
 * @param tokenType always {@code Bearer}
 * @param expiresIn token lifetime in seconds (900)
 */
public record TokenResponse(String accessToken, String tokenType, long expiresIn) {

  /** The only token type issued. */
  public static final String BEARER = "Bearer";
}

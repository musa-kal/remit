package ca.musakaleem.remit.api.security;

import java.time.Instant;
import java.util.Set;

/**
 * The verified claims of an access token.
 *
 * @param username {@code sub}
 * @param roles {@code roles}, for example OPERATOR and ADMIN
 * @param tokenId {@code jti}
 * @param issuedAt {@code iat}
 * @param expiresAt {@code exp}
 */
public record TokenClaims(
    String username, Set<String> roles, String tokenId, Instant issuedAt, Instant expiresAt) {}

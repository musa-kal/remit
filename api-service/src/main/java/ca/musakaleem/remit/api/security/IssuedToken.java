package ca.musakaleem.remit.api.security;

import java.time.Instant;

/**
 * A freshly signed access token.
 *
 * @param token compact JWS
 * @param expiresInSeconds lifetime in seconds (900)
 * @param expiresAt expiry instant
 */
public record IssuedToken(String token, long expiresInSeconds, Instant expiresAt) {}

package ca.musakaleem.remit.api.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Component;

/**
 * Issues and verifies HS256 access tokens with jjwt (FR-SEC-01,
 * docs/decisions/0004-jwt-approach.md). Claims: {@code sub}, {@code roles}, {@code iat}, {@code
 * exp} and {@code jti}. Time comes from the injected {@link Clock}, so tests control expiry.
 */
@Component
public class JwtService {

  /** HS256 needs a key of at least 256 bits. */
  public static final int MIN_SECRET_BYTES = 32;

  /** Claim holding the role names, for example {@code ["OPERATOR","ADMIN"]}. */
  public static final String ROLES_CLAIM = "roles";

  private static final String ALGORITHM = "HS256";

  private final SecretKey key;
  private final Duration ttl;
  private final Clock clock;
  private final JwtParser parser;

  /**
   * Creates the service.
   *
   * @param properties secret and lifetime
   * @param clock clock for {@code iat}, {@code exp} and expiry checks
   * @throws IllegalStateException when the secret is missing or shorter than 32 bytes
   */
  public JwtService(JwtProperties properties, Clock clock) {
    this.key = signingKey(properties.secret());
    this.ttl = properties.ttl();
    this.clock = clock;
    this.parser =
        Jwts.parser().verifyWith(key).clock(() -> Date.from(this.clock.instant())).build();
  }

  /**
   * Builds the HMAC key from the configured secret, refusing a missing or short one.
   *
   * @param secret the value of {@code REMIT_JWT_SECRET}
   * @return the key
   */
  static SecretKey signingKey(String secret) {
    if (secret == null || secret.isBlank()) {
      throw new IllegalStateException(
          "REMIT_JWT_SECRET is not set: api-service needs an HS256 signing secret of at least "
              + MIN_SECRET_BYTES
              + " bytes (generate one with: openssl rand -base64 48)");
    }
    byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
    if (bytes.length < MIN_SECRET_BYTES) {
      throw new IllegalStateException(
          "REMIT_JWT_SECRET is "
              + bytes.length
              + " bytes long; HS256 needs at least "
              + MIN_SECRET_BYTES
              + " bytes (generate one with: openssl rand -base64 48)");
    }
    return Keys.hmacShaKeyFor(bytes);
  }

  /**
   * Signs a token for a user.
   *
   * @param username the subject
   * @param roles role names without the {@code ROLE_} prefix; at least one
   * @return the token and its lifetime
   */
  public IssuedToken issue(String username, Collection<String> roles) {
    if (roles == null || roles.isEmpty()) {
      throw new IllegalArgumentException("A token needs at least one role");
    }
    Instant now = clock.instant();
    Instant expiresAt = now.plus(ttl);
    String token =
        Jwts.builder()
            .subject(username)
            .claim(ROLES_CLAIM, List.copyOf(roles))
            .issuedAt(Date.from(now))
            .expiration(Date.from(expiresAt))
            .id(UUID.randomUUID().toString())
            .signWith(key, Jwts.SIG.HS256)
            .compact();
    return new IssuedToken(token, ttl.toSeconds(), expiresAt);
  }

  /**
   * Verifies the signature and expiry of a token and returns its claims.
   *
   * @param token compact JWS
   * @return the claims
   * @throws InvalidTokenException when the token is malformed, badly signed, not HS256, expired, or
   *     has no subject, roles or expiry
   */
  public TokenClaims parse(String token) {
    Jws<Claims> jws;
    try {
      jws = parser.parseSignedClaims(token);
    } catch (ExpiredJwtException e) {
      throw new InvalidTokenException("The access token has expired", e);
    } catch (JwtException | IllegalArgumentException e) {
      throw new InvalidTokenException("The access token is invalid", e);
    }
    if (!ALGORITHM.equals(jws.getHeader().getAlgorithm())) {
      throw new InvalidTokenException("The access token is not signed with " + ALGORITHM);
    }
    Claims claims = jws.getPayload();
    if (claims.getSubject() == null || claims.getSubject().isBlank()) {
      throw new InvalidTokenException("The access token has no subject");
    }
    if (claims.getExpiration() == null || claims.getIssuedAt() == null) {
      throw new InvalidTokenException("The access token has no issue or expiry time");
    }
    return new TokenClaims(
        claims.getSubject(),
        roles(claims),
        claims.getId(),
        claims.getIssuedAt().toInstant(),
        claims.getExpiration().toInstant());
  }

  private static Set<String> roles(Claims claims) {
    if (!(claims.get(ROLES_CLAIM) instanceof List<?> values) || values.isEmpty()) {
      throw new InvalidTokenException("The access token has no roles");
    }
    Set<String> roles = new LinkedHashSet<>();
    for (Object value : values) {
      if (!(value instanceof String role) || role.isBlank()) {
        throw new InvalidTokenException("The access token has an invalid role");
      }
      roles.add(role);
    }
    return Set.copyOf(roles);
  }
}

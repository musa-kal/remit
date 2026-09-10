package ca.musakaleem.remit.api.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.musakaleem.remit.api.support.MutableClock;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class JwtServiceTest {

  private static final String SECRET = "unit-test-secret-that-is-longer-than-32-bytes";
  private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");

  private final MutableClock clock = new MutableClock(NOW);
  private final JwtService jwt = service(SECRET);

  @Test
  void issuesAFifteenMinuteHs256TokenWithAllRequiredClaims() {
    IssuedToken issued = jwt.issue("admin", List.of("OPERATOR", "ADMIN"));

    TokenClaims claims = jwt.parse(issued.token());

    assertThat(issued.expiresInSeconds()).isEqualTo(900);
    assertThat(issued.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
    assertThat(claims.username()).isEqualTo("admin");
    assertThat(claims.roles()).containsExactlyInAnyOrder("OPERATOR", "ADMIN");
    assertThat(claims.issuedAt()).isEqualTo(NOW);
    assertThat(claims.expiresAt()).isEqualTo(NOW.plusSeconds(900));
    assertThat(UUID.fromString(claims.tokenId())).isNotNull();
    assertThat(header(issued.token())).contains("\"alg\":\"HS256\"");
  }

  @Test
  void everyTokenGetsItsOwnId() {
    String first = jwt.parse(jwt.issue("operator", List.of("OPERATOR")).token()).tokenId();
    String second = jwt.parse(jwt.issue("operator", List.of("OPERATOR")).token()).tokenId();

    assertThat(first).isNotEqualTo(second);
  }

  @Test
  void acceptsATokenUntilItExpiresAndRefusesItAfterwards() {
    String token = jwt.issue("operator", List.of("OPERATOR")).token();

    clock.advance(Duration.ofMinutes(14).plusSeconds(59));
    assertThat(jwt.parse(token).username()).isEqualTo("operator");

    clock.advance(Duration.ofSeconds(2));
    assertThatThrownBy(() -> jwt.parse(token))
        .isInstanceOf(InvalidTokenException.class)
        .hasMessage("The access token has expired");
  }

  @Test
  void refusesATokenSignedWithAnotherSecret() {
    String foreign =
        service("another-secret-that-is-also-longer-than-32-bytes")
            .issue("admin", List.of("ADMIN"))
            .token();

    assertThatThrownBy(() -> jwt.parse(foreign))
        .isInstanceOf(InvalidTokenException.class)
        .hasMessage("The access token is invalid");
  }

  @Test
  void refusesATamperedPayload() {
    String token = jwt.issue("operator", List.of("OPERATOR")).token();
    String[] parts = token.split("\\.");
    String forgedPayload =
        java.util.Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                "{\"sub\":\"operator\",\"roles\":[\"ADMIN\"]}".getBytes(StandardCharsets.UTF_8));

    assertThatThrownBy(() -> jwt.parse(parts[0] + "." + forgedPayload + "." + parts[2]))
        .isInstanceOf(InvalidTokenException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"not-a-jwt", "a.b.c", ""})
  void refusesGarbage(String token) {
    assertThatThrownBy(() -> jwt.parse(token)).isInstanceOf(InvalidTokenException.class);
  }

  @Test
  void refusesAnUnsignedToken() {
    String unsigned =
        Jwts.builder()
            .subject("admin")
            .claim("roles", List.of("ADMIN"))
            .issuedAt(Date.from(NOW))
            .expiration(Date.from(NOW.plusSeconds(60)))
            .compact();

    assertThatThrownBy(() -> jwt.parse(unsigned)).isInstanceOf(InvalidTokenException.class);
  }

  @Test
  void refusesATokenWithoutRoles() {
    String token = signed(builder -> builder.subject("operator"));

    assertThatThrownBy(() -> jwt.parse(token))
        .isInstanceOf(InvalidTokenException.class)
        .hasMessage("The access token has no roles");
  }

  @Test
  void refusesATokenWithAnEmptyOrMalformedRoleList() {
    String empty = signed(builder -> builder.subject("operator").claim("roles", List.of()));
    String notAList = signed(builder -> builder.subject("operator").claim("roles", "ADMIN"));
    String numbers = signed(builder -> builder.subject("operator").claim("roles", List.of(1, 2)));

    assertThatThrownBy(() -> jwt.parse(empty)).hasMessage("The access token has no roles");
    assertThatThrownBy(() -> jwt.parse(notAList)).hasMessage("The access token has no roles");
    assertThatThrownBy(() -> jwt.parse(numbers)).hasMessage("The access token has an invalid role");
  }

  @Test
  void refusesATokenWithoutSubjectOrExpiry() {
    String noSubject = signed(builder -> builder.claim("roles", List.of("ADMIN")));
    String noExpiry =
        Jwts.builder()
            .subject("admin")
            .claim("roles", List.of("ADMIN"))
            .issuedAt(Date.from(NOW))
            .signWith(key(SECRET), Jwts.SIG.HS256)
            .compact();

    assertThatThrownBy(() -> jwt.parse(noSubject)).hasMessage("The access token has no subject");
    assertThatThrownBy(() -> jwt.parse(noExpiry))
        .hasMessage("The access token has no issue or expiry time");
  }

  @Test
  void refusesAnotherHmacAlgorithmEvenWithTheSameKey() {
    String longSecret = "a-secret-long-enough-for-hs384-signing-0123456789abcdef";
    String hs384 =
        Jwts.builder()
            .subject("admin")
            .claim("roles", List.of("ADMIN"))
            .issuedAt(Date.from(NOW))
            .expiration(Date.from(NOW.plusSeconds(60)))
            .signWith(key(longSecret), Jwts.SIG.HS384)
            .compact();

    assertThatThrownBy(() -> service(longSecret).parse(hs384))
        .isInstanceOf(InvalidTokenException.class)
        .hasMessage("The access token is not signed with HS256");
  }

  @Test
  void refusesToIssueATokenWithoutRoles() {
    assertThatThrownBy(() -> jwt.issue("operator", Set.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"   "})
  void failsFastWhenTheSecretIsMissing(String secret) {
    assertThatThrownBy(() -> service(secret))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageStartingWith("REMIT_JWT_SECRET is not set");
  }

  @Test
  void failsFastWhenTheSecretIsShorterThan32Bytes() {
    assertThatThrownBy(() -> service("only-31-bytes-long-secret-value"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageStartingWith("REMIT_JWT_SECRET is 31 bytes long; HS256 needs at least 32 bytes");
  }

  @Test
  void acceptsASecretOfExactly32Bytes() {
    JwtService exact = service("x".repeat(32));

    assertThat(exact.parse(exact.issue("operator", List.of("OPERATOR")).token()).username())
        .isEqualTo("operator");
  }

  private JwtService service(String secret) {
    return new JwtService(new JwtProperties(secret, Duration.ofMinutes(15)), clock);
  }

  private String signed(java.util.function.UnaryOperator<io.jsonwebtoken.JwtBuilder> customizer) {
    return customizer
        .apply(
            Jwts.builder()
                .issuedAt(Date.from(NOW))
                .expiration(Date.from(NOW.plusSeconds(60)))
                .id(UUID.randomUUID().toString()))
        .signWith(key(SECRET), Jwts.SIG.HS256)
        .compact();
  }

  private static SecretKey key(String secret) {
    return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
  }

  private static String header(String token) {
    return new String(
        java.util.Base64.getUrlDecoder().decode(token.split("\\.")[0]), StandardCharsets.UTF_8);
  }
}

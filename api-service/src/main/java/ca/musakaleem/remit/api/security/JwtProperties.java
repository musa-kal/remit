package ca.musakaleem.remit.api.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Token settings bound from {@code remit.security.jwt} (FR-SEC-01). The secret is checked by {@link
 * JwtService} so a missing or short value fails startup with a message naming the variable.
 *
 * @param secret HS256 signing secret from {@code REMIT_JWT_SECRET}, at least 32 bytes
 * @param ttl how long an issued token is valid
 */
@ConfigurationProperties("remit.security.jwt")
public record JwtProperties(String secret, @DefaultValue("15m") Duration ttl) {}

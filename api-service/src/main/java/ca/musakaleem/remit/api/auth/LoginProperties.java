package ca.musakaleem.remit.api.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Login lockout settings bound from {@code remit.security.login} (FR-SEC-06).
 *
 * @param maxFailures failures within {@code window} that lock a username
 * @param window how far back failures count
 * @param lockDuration how long a locked username stays locked
 */
@ConfigurationProperties("remit.security.login")
public record LoginProperties(
    @DefaultValue("5") int maxFailures,
    @DefaultValue("10m") Duration window,
    @DefaultValue("10m") Duration lockDuration) {}

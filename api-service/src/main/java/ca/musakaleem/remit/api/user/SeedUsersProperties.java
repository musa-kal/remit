package ca.musakaleem.remit.api.user;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings of {@link UserSeeder}, bound from {@code remit.security.seed-users}.
 *
 * @param enabled whether to create the two demo accounts when missing (local and docker profiles)
 * @param operatorPassword password of {@code operator}, from {@code REMIT_OPERATOR_PASSWORD}
 * @param adminPassword password of {@code admin}, from {@code REMIT_ADMIN_PASSWORD}
 */
@ConfigurationProperties("remit.security.seed-users")
public record SeedUsersProperties(boolean enabled, String operatorPassword, String adminPassword) {}

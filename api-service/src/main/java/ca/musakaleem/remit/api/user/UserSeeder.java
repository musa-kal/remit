package ca.musakaleem.remit.api.user;

import java.time.Clock;
import java.util.EnumSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Creates the demo accounts {@code operator} (OPERATOR) and {@code admin} (OPERATOR, ADMIN) at
 * startup when they do not exist yet (FR-SEC-02). Passwords come from {@code
 * REMIT_OPERATOR_PASSWORD} and {@code REMIT_ADMIN_PASSWORD} and are stored as BCrypt hashes, so
 * nothing secret is committed. Enabled in the local and docker profiles only; an existing account
 * is never changed. See docs/decisions/0004-jwt-approach.md for why this is a Java seeder rather
 * than a Flyway migration.
 */
@Component
@ConditionalOnProperty(name = "remit.security.seed-users.enabled", havingValue = "true")
public class UserSeeder implements ApplicationRunner {

  /** Login name of the seeded read-only account. */
  public static final String OPERATOR = "operator";

  /** Login name of the seeded administrator account. */
  public static final String ADMIN = "admin";

  private static final Logger log = LoggerFactory.getLogger(UserSeeder.class);

  private final AppUserRepository users;
  private final PasswordEncoder passwordEncoder;
  private final SeedUsersProperties properties;
  private final Clock clock;

  /**
   * Creates the seeder.
   *
   * @param users account repository
   * @param passwordEncoder BCrypt encoder
   * @param properties seeded passwords
   * @param clock clock for {@code created_at}
   */
  public UserSeeder(
      AppUserRepository users,
      PasswordEncoder passwordEncoder,
      SeedUsersProperties properties,
      Clock clock) {
    this.users = users;
    this.passwordEncoder = passwordEncoder;
    this.properties = properties;
    this.clock = clock;
  }

  @Override
  public void run(ApplicationArguments args) {
    seed(
        OPERATOR,
        properties.operatorPassword(),
        EnumSet.of(Role.OPERATOR),
        "REMIT_OPERATOR_PASSWORD");
    seed(
        ADMIN,
        properties.adminPassword(),
        EnumSet.of(Role.OPERATOR, Role.ADMIN),
        "REMIT_ADMIN_PASSWORD");
  }

  private void seed(String username, String password, Set<Role> roles, String variable) {
    if (users.existsByUsername(username)) {
      log.info("Seed user {} already exists; left unchanged", username);
      return;
    }
    if (password == null || password.isBlank()) {
      throw new IllegalStateException(
          variable + " must be set to seed the " + username + " user (remit.security.seed-users)");
    }
    try {
      users.saveAndFlush(
          new AppUser(username, passwordEncoder.encode(password), roles, clock.instant()));
      log.info("Seeded user {} with roles {}", username, roles);
    } catch (DataIntegrityViolationException e) {
      // Another instance seeded the same user at the same moment.
      log.info("Seed user {} was created concurrently; left unchanged", username);
    }
  }
}

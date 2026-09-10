package ca.musakaleem.remit.api.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

class UserSeederTest {

  private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");

  private final AppUserRepository users = mock(AppUserRepository.class);
  private final PasswordEncoder encoder = new BCryptPasswordEncoder(4);
  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

  @Test
  void seedsOperatorAndAdminWithBcryptHashesAndTheirRoles() {
    seeder("op-secret", "admin-secret").run(null);

    ArgumentCaptor<AppUser> saved = ArgumentCaptor.forClass(AppUser.class);
    verify(users, times(2)).saveAndFlush(saved.capture());
    List<AppUser> seeded = saved.getAllValues();
    assertThat(seeded).extracting(AppUser::getUsername).containsExactly("operator", "admin");
    assertThat(seeded.get(0).getRoles()).containsExactly(Role.OPERATOR);
    assertThat(seeded.get(1).getRoles()).containsExactlyInAnyOrder(Role.OPERATOR, Role.ADMIN);
    assertThat(seeded.get(0).getPasswordHash()).startsWith("$2a$").isNotEqualTo("op-secret");
    assertThat(encoder.matches("op-secret", seeded.get(0).getPasswordHash())).isTrue();
    assertThat(encoder.matches("admin-secret", seeded.get(1).getPasswordHash())).isTrue();
    assertThat(seeded).allSatisfy(u -> assertThat(u.isEnabled()).isTrue());
    assertThat(seeded).allSatisfy(u -> assertThat(u.getCreatedAt()).isEqualTo(NOW));
  }

  @Test
  void leavesExistingUsersUnchangedEvenWithoutPasswords() {
    when(users.existsByUsername(any())).thenReturn(true);

    seeder(null, "").run(null);

    verify(users, never()).saveAndFlush(any());
  }

  @Test
  void failsStartupWhenAPasswordForAMissingUserIsNotSet() {
    assertThatThrownBy(() -> seeder(" ", "admin-secret").run(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageStartingWith("REMIT_OPERATOR_PASSWORD must be set");
    assertThatThrownBy(() -> seeder("op-secret", null).run(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageStartingWith("REMIT_ADMIN_PASSWORD must be set");
  }

  @Test
  void toleratesAnotherInstanceSeedingAtTheSameTime() {
    when(users.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate"));

    seeder("op-secret", "admin-secret").run(null);

    verify(users, times(2)).saveAndFlush(any());
  }

  @Test
  void userDetailsCarryRoleAuthoritiesAndTheEnabledFlag() {
    AppUser admin = new AppUser("admin", "hash", Set.of(Role.OPERATOR, Role.ADMIN), NOW);
    AppUser disabled = new AppUser("gone", "hash", Set.of(Role.OPERATOR), NOW);
    disabled.disable();
    when(users.findByUsername("admin")).thenReturn(Optional.of(admin));
    when(users.findByUsername("gone")).thenReturn(Optional.of(disabled));
    AppUserDetailsService service = new AppUserDetailsService(users);

    UserDetails details = service.loadUserByUsername("admin");

    assertThat(details.getUsername()).isEqualTo("admin");
    assertThat(details.getPassword()).isEqualTo("hash");
    assertThat(details.getAuthorities())
        .extracting(GrantedAuthority::getAuthority)
        .containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_OPERATOR");
    assertThat(service.loadUserByUsername("gone").isEnabled()).isFalse();
    assertThatThrownBy(() -> service.loadUserByUsername("nobody"))
        .isInstanceOf(UsernameNotFoundException.class);
  }

  @Test
  void aUserNeedsARole() {
    assertThatThrownBy(() -> new AppUser("x", "hash", Set.of(), NOW))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private UserSeeder seeder(String operatorPassword, String adminPassword) {
    return new UserSeeder(
        users, encoder, new SeedUsersProperties(true, operatorPassword, adminPassword), clock);
  }
}

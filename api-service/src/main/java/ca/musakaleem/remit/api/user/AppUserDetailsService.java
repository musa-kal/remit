package ca.musakaleem.remit.api.user;

import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads operator accounts for Spring Security's password check at login. Roles become {@code
 * ROLE_OPERATOR} and {@code ROLE_ADMIN} authorities.
 */
@Service
public class AppUserDetailsService implements UserDetailsService {

  private final AppUserRepository users;

  /**
   * Creates the service.
   *
   * @param users account repository
   */
  public AppUserDetailsService(AppUserRepository users) {
    this.users = users;
  }

  @Override
  @Transactional(readOnly = true)
  public UserDetails loadUserByUsername(String username) {
    AppUser user =
        users
            .findByUsername(username)
            .orElseThrow(() -> new UsernameNotFoundException("Unknown user"));
    return User.withUsername(user.getUsername())
        .password(user.getPasswordHash())
        .disabled(!user.isEnabled())
        .roles(user.getRoles().stream().map(Role::name).sorted().toArray(String[]::new))
        .build();
  }
}

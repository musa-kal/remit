package ca.musakaleem.remit.api.user;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Operator accounts. Username comparison follows the case-insensitive column collation. */
public interface AppUserRepository extends JpaRepository<AppUser, Long> {

  /**
   * Finds an account by login name.
   *
   * @param username login name
   * @return the account, if any
   */
  Optional<AppUser> findByUsername(String username);

  /**
   * Tells whether an account exists.
   *
   * @param username login name
   * @return true when it exists
   */
  boolean existsByUsername(String username);
}

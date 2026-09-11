package ca.musakaleem.remit.api.auth;

import java.time.Instant;

/** A login for a username that is locked after too many failures; answered with 429. */
public class LoginLockedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final Instant lockedUntil;

  /**
   * Creates the exception.
   *
   * @param lockedUntil when the lock ends
   */
  public LoginLockedException(Instant lockedUntil) {
    super("Too many failed logins; try again later");
    this.lockedUntil = lockedUntil;
  }

  /**
   * When the lock ends.
   *
   * @return the instant
   */
  public Instant getLockedUntil() {
    return lockedUntil;
  }
}

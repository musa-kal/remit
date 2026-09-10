package ca.musakaleem.remit.api.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Counts failed logins per username and locks a username after too many (FR-SEC-06): by default 5
 * failures within 10 minutes lock it for 10 minutes, even for the right password.
 *
 * <p>State is in memory, which the spec allows: it is per instance and lost on restart, so with
 * several replicas an attacker gets 5 attempts per replica. Usernames are compared case
 * insensitively, like the database column. Unknown usernames are tracked too, so a lockout reveals
 * nothing about which accounts exist.
 */
@Component
public class LoginAttemptService {

  private final Map<String, Attempts> attempts = new ConcurrentHashMap<>();
  private final int maxFailures;
  private final Duration window;
  private final Duration lockDuration;
  private final Clock clock;

  /**
   * Creates the service.
   *
   * @param properties thresholds
   * @param clock clock for failure times and lock expiry
   */
  public LoginAttemptService(LoginProperties properties, Clock clock) {
    this.maxFailures = properties.maxFailures();
    this.window = properties.window();
    this.lockDuration = properties.lockDuration();
    this.clock = clock;
  }

  /**
   * Tells whether a username is locked right now.
   *
   * @param username login name as typed
   * @return when the lock ends, or empty when not locked
   */
  public Optional<Instant> lockedUntil(String username) {
    Attempts entry = attempts.get(key(username));
    if (entry == null) {
      return Optional.empty();
    }
    synchronized (entry) {
      Instant now = clock.instant();
      return entry.lockedUntil != null && now.isBefore(entry.lockedUntil)
          ? Optional.of(entry.lockedUntil)
          : Optional.empty();
    }
  }

  /**
   * Records a failed login; the failure that reaches the threshold starts the lock.
   *
   * @param username login name as typed
   * @return true when the username is locked after this failure
   */
  public boolean recordFailure(String username) {
    Attempts entry = attempts.computeIfAbsent(key(username), k -> new Attempts());
    synchronized (entry) {
      Instant now = clock.instant();
      if (entry.lockedUntil != null && !now.isBefore(entry.lockedUntil)) {
        // The previous lock is over: start counting again.
        entry.lockedUntil = null;
        entry.failures.clear();
      }
      Instant windowStart = now.minus(window);
      while (!entry.failures.isEmpty() && !entry.failures.peekFirst().isAfter(windowStart)) {
        entry.failures.removeFirst();
      }
      entry.failures.addLast(now);
      if (entry.failures.size() >= maxFailures) {
        entry.lockedUntil = now.plus(lockDuration);
        entry.failures.clear();
      }
      return entry.lockedUntil != null;
    }
  }

  /**
   * Forgets the failures of a username after a successful login.
   *
   * @param username login name as typed
   */
  public void recordSuccess(String username) {
    attempts.remove(key(username));
  }

  private static String key(String username) {
    return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
  }

  /** Failure times inside the window and the end of the current lock; guarded by itself. */
  private static final class Attempts {
    private final Deque<Instant> failures = new ArrayDeque<>();
    private Instant lockedUntil;
  }
}

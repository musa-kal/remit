package ca.musakaleem.remit.api.auth;

import static org.assertj.core.api.Assertions.assertThat;

import ca.musakaleem.remit.api.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class LoginAttemptServiceTest {

  private static final Instant START = Instant.parse("2026-10-15T12:00:00Z");

  private final MutableClock clock = new MutableClock(START);
  private final LoginAttemptService service =
      new LoginAttemptService(
          new LoginProperties(5, Duration.ofMinutes(10), Duration.ofMinutes(10)), clock);

  @Test
  void fourFailuresDoNotLock() {
    for (int i = 0; i < 4; i++) {
      assertThat(service.recordFailure("operator")).isFalse();
    }

    assertThat(service.lockedUntil("operator")).isEmpty();
  }

  @Test
  void theFifthFailureWithinTenMinutesLocksForTenMinutes() {
    for (int i = 0; i < 4; i++) {
      service.recordFailure("operator");
      clock.advance(Duration.ofMinutes(2));
    }

    assertThat(service.recordFailure("operator")).isTrue();

    Instant lockStart = START.plus(Duration.ofMinutes(8));
    assertThat(service.lockedUntil("operator")).contains(lockStart.plus(Duration.ofMinutes(10)));
    clock.advance(Duration.ofMinutes(9).plusSeconds(59));
    assertThat(service.lockedUntil("operator")).isPresent();
    clock.advance(Duration.ofSeconds(1));
    assertThat(service.lockedUntil("operator")).isEmpty();
  }

  @Test
  void failuresOlderThanTheWindowDoNotCount() {
    for (int i = 0; i < 4; i++) {
      service.recordFailure("operator");
    }
    clock.advance(Duration.ofMinutes(10));

    assertThat(service.recordFailure("operator")).isFalse();
    assertThat(service.lockedUntil("operator")).isEmpty();
  }

  @Test
  void countingStartsAfreshOnceALockHasExpired() {
    lock("operator");
    clock.advance(Duration.ofMinutes(10));

    assertThat(service.recordFailure("operator")).isFalse();
    assertThat(service.lockedUntil("operator")).isEmpty();
  }

  @Test
  void aSuccessfulLoginClearsTheFailures() {
    for (int i = 0; i < 4; i++) {
      service.recordFailure("operator");
    }

    service.recordSuccess("operator");

    assertThat(service.recordFailure("operator")).isFalse();
  }

  @Test
  void usernamesAreComparedCaseInsensitivelyAndIndependently() {
    lock("Operator ");

    assertThat(service.lockedUntil("operator")).isPresent();
    assertThat(service.lockedUntil("admin")).isEmpty();
    assertThat(service.lockedUntil(null)).isEmpty();
  }

  private void lock(String username) {
    for (int i = 0; i < 5; i++) {
      service.recordFailure(username);
    }
  }
}

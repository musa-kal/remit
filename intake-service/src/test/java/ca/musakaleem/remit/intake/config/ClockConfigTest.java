package ca.musakaleem.remit.intake.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;

class ClockConfigTest {

  private final ClockConfig config = new ClockConfig();

  @Test
  void usesTheSystemUtcClockByDefault() {
    Clock clock = config.clock("");

    assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
    assertThat(Duration.between(clock.instant(), Instant.now()).abs())
        .isLessThan(Duration.ofSeconds(5));
    assertThat(config.clock(null).getZone()).isEqualTo(ZoneOffset.UTC);
  }

  @Test
  void fixedDateShiftsTheBusinessDateButKeepsTicking() throws InterruptedException {
    Clock clock = config.clock(" 2026-10-15 ");

    assertThat(BusinessCalendar.today(clock)).isEqualTo(LocalDate.of(2026, 10, 15));
    Instant first = clock.instant();
    Thread.sleep(5);
    assertThat(clock.instant()).isAfter(first);
  }

  @Test
  void rejectsAnInvalidFixedDate() {
    assertThatExceptionOfType(DateTimeParseException.class)
        .isThrownBy(() -> config.clock("15/10/2026"));
  }

  @Test
  void businessDateIsTorontoNotUtc() {
    // 03:30 UTC on Oct 16 is still 23:30 on Oct 15 in Toronto (EDT, UTC-4).
    Clock lateEvening = Clock.fixed(Instant.parse("2026-10-16T03:30:00Z"), ZoneOffset.UTC);

    assertThat(BusinessCalendar.today(lateEvening)).isEqualTo(LocalDate.of(2026, 10, 15));
  }

  @Test
  void shiftKeepsTheTorontoTimeOfDayAcrossADaylightSavingChange() {
    // 2026-11-01 is the end of daylight saving in Toronto: 23:30 EDT becomes 23:30 EST.
    Clock base = Clock.fixed(Instant.parse("2026-10-16T03:30:00Z"), ZoneOffset.UTC);

    Clock shifted = BusinessCalendar.shiftedTo(base, LocalDate.of(2026, 11, 20));

    assertThat(BusinessCalendar.today(shifted)).isEqualTo(LocalDate.of(2026, 11, 20));
    assertThat(shifted.instant()).isEqualTo(Instant.parse("2026-11-21T04:30:00Z"));
    assertThat(ChronoUnit.DAYS.between(base.instant(), shifted.instant())).isEqualTo(36);
  }
}

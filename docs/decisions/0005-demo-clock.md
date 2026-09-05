# ADR 0005: Demo clock and sample value dates

- Status: accepted
- Date: 2026-09-30

## Context

Spec 3.3 says a `value_date` must not be before today (America/Toronto) and not more than 30 days ahead. The sample files in `samples/` are committed once and never change, so any fixed value date they carry becomes invalid 30 days after the window it was written for. Moving the dates forward in every file by hand, or generating the samples at build time, would make the sample walkthrough and the expected outcomes in `docs/e2e-expectations.md` drift.

NFR-04 already requires an injected `java.time.Clock` so tests control time.

## Decision

- Every valid sample row uses value date `2026-10-15`, the date in the sample file names. The one row that must fail the value date rule uses `2026-01-02`, which is in the past for any clock after that day.
- intake-service reads the business date from the injected `Clock` (`BusinessCalendar.today(clock)`, zone America/Toronto). Timestamps are stored in UTC.
- An optional property `remit.clock.fixed-date` (env `REMIT_CLOCK_FIXED_DATE`, ISO date) pins the business date. It is not a frozen clock: `BusinessCalendar.shiftedTo` offsets the system clock by whole days, keeping the Toronto time of day, so timestamps keep increasing and event ordering by `occurredAt` still works. Startup logs a warning when it is set.
- The `test` profile sets `remit.clock.fixed-date: 2026-10-15`, so the integration tests pass on any date. Unit tests pass a `Clock.fixed(...)` directly.
- The `local`, `docker` and `k8s` profiles leave it empty (real date) unless `REMIT_CLOCK_FIXED_DATE` is set. The sample files are valid against the real clock from 2026-09-15 to 2026-10-15; after that the demo needs `REMIT_CLOCK_FIXED_DATE=2026-10-15`.
- `scripts/gen-batch.sh` writes tomorrow's date (America/Toronto) by default, or `VALUE_DATE`, so generated files are valid against the real clock.

## Consequences

- The samples stay byte-stable, which the duplicate-file sample (byte-identical copy) depends on.
- A misconfigured production deployment could pin the date. The property is documented as demo and test only, is empty by default, and is logged at WARN when used.
- routing-service and api-service should take the same property if they ever apply date rules, so a demo stays consistent across services.

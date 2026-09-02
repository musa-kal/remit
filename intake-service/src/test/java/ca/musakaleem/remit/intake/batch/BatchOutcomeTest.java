package ca.musakaleem.remit.intake.batch;

import static org.assertj.core.api.Assertions.assertThat;

import ca.musakaleem.remit.common.event.BatchStatus;
import ca.musakaleem.remit.intake.validation.ReasonCode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class BatchOutcomeTest {

  @ParameterizedTest(name = "total={0} rejected={1} skipLimitHit={2} limit={3} -> {4} {5}")
  @CsvSource({
    "10, 0, false, 1000, COMPLETED,",
    "20, 5, false, 1000, COMPLETED_WITH_ERRORS,",
    "20, 10, false, 1000, COMPLETED_WITH_ERRORS,",
    "20, 11, false, 1000, FAILED, REJECTION_THRESHOLD_EXCEEDED",
    "3, 1, false, 1000, COMPLETED_WITH_ERRORS,",
    "3, 2, false, 1000, FAILED, REJECTION_THRESHOLD_EXCEEDED",
    "1, 1, false, 1000, FAILED, REJECTION_THRESHOLD_EXCEEDED",
    "5000, 1000, false, 1000, COMPLETED_WITH_ERRORS,",
    "5000, 1001, false, 1000, FAILED, SKIP_LIMIT_EXCEEDED",
    "10000, 3, false, 2, FAILED, SKIP_LIMIT_EXCEEDED",
    "10000, 2, true, 2, FAILED, SKIP_LIMIT_EXCEEDED",
    "10, 0, true, 0, FAILED, SKIP_LIMIT_EXCEEDED",
  })
  void appliesTheSkipLimitAndFiftyPercentRules(
      int total, int rejected, boolean hit, int limit, BatchStatus status, ReasonCode reason) {
    BatchOutcome outcome = BatchOutcome.decide(total, rejected, hit, limit);

    assertThat(outcome.status()).isEqualTo(status);
    assertThat(outcome.reason()).isEqualTo(reason);
  }
}

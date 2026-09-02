package ca.musakaleem.remit.intake.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class BatchFileNameTest {

  @Test
  void parsesClientIdAndTimestamp() {
    BatchFileName name = BatchFileName.parse("PAY_ACME01_20261015093000.csv").orElseThrow();

    assertThat(name.fileName()).isEqualTo("PAY_ACME01_20261015093000.csv");
    assertThat(name.clientId()).isEqualTo("ACME01");
    assertThat(name.createdAt()).isEqualTo(LocalDateTime.of(2026, 10, 15, 9, 30, 0));
  }

  @ParameterizedTest(name = "{0} -> {1}")
  @CsvSource({
    "PAY_ABC_20261015093000.csv, ABC",
    "PAY_ABCDEFGHIJ_20261015093000.csv, ABCDEFGHIJ",
    "PAY_123_20261231235959.csv, 123",
    "PAY_BETA02_20240229000000.csv, BETA02",
  })
  void acceptsValidNames(String fileName, String clientId) {
    assertThat(BatchFileName.parse(fileName))
        .get()
        .extracting(BatchFileName::clientId)
        .isEqualTo(clientId);
  }

  @ParameterizedTest(name = "rejects \"{0}\"")
  @NullAndEmptySource
  @ValueSource(
      strings = {
        "PAY_AB_20261015093000.csv",
        "PAY_ABCDEFGHIJK_20261015093000.csv",
        "PAY_acme01_20261015093000.csv",
        "PAY_ACME-1_20261015093000.csv",
        "PAY_ACME01_2026101509300.csv",
        "PAY_ACME01_202610150930000.csv",
        "PAY_ACME01_20261015093000.CSV",
        "PAY_ACME01_20261015093000.csv.bak",
        "pay_ACME01_20261015093000.csv",
        "PAYMENT_ACME01_20261015093000.csv",
        "PAY_ACME01_20261015093000.txt",
        " PAY_ACME01_20261015093000.csv",
        "PAY_ACME01_20261315093000.csv",
        "PAY_ACME01_20261032093000.csv",
        "PAY_ACME01_20250229093000.csv",
        "PAY_ACME01_20261015243000.csv",
        "PAY_ACME01_20261015096000.csv",
        "payments.csv",
      })
  void rejectsInvalidNames(String fileName) {
    assertThat(BatchFileName.parse(fileName)).isEmpty();
  }
}

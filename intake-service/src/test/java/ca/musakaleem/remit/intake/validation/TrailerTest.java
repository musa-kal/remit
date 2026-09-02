package ca.musakaleem.remit.intake.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class TrailerTest {

  @ParameterizedTest(name = "{0}")
  @CsvSource(
      delimiter = '|',
      value = {
        "TRAILER,10,1234.56|10|1234.56",
        "TRAILER,0,0.00|0|0.00",
        "TRAILER,10000,999999999999.99|10000|999999999999.99",
        "TRAILER,007,10.00|7|10.00",
      })
  void parsesWellFormedTrailers(String line, int count, String total) {
    Trailer trailer = Trailer.parse(line).orElseThrow();

    assertThat(trailer.recordCount()).isEqualTo(count);
    assertThat(trailer.controlTotal()).isEqualByComparingTo(total);
  }

  @ParameterizedTest(name = "rejects \"{0}\"")
  @NullAndEmptySource
  @ValueSource(
      strings = {
        "TRAILER,10",
        "TRAILER,10,1234.5",
        "TRAILER,10,1234",
        "TRAILER,10,1234.567",
        "TRAILER,-1,10.00",
        "TRAILER,10,-10.00",
        "TRAILER,ten,10.00",
        "TRAILER,10,10.00,extra",
        "trailer,10,10.00",
        " TRAILER,10,10.00",
        "TRAILER, 10,10.00",
        "TRAILER,1234567890,10.00",
        "ACME-0001,Jane Doe,001,00011,1234567",
      })
  void rejectsMalformedTrailers(String line) {
    assertThat(Trailer.parse(line)).isEmpty();
  }

  @Test
  void controlTotalComparisonIgnoresScale() {
    Trailer trailer = Trailer.parse("TRAILER,2,30.00").orElseThrow();

    assertThat(trailer.controlTotalMatches(new BigDecimal("30"))).isTrue();
    assertThat(trailer.controlTotalMatches(new BigDecimal("30.000"))).isTrue();
    assertThat(trailer.controlTotalMatches(new BigDecimal("30.01"))).isFalse();
    assertThat(trailer.controlTotalMatches(new BigDecimal("29.99"))).isFalse();
  }
}

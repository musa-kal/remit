package ca.musakaleem.remit.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MaskingUtilTest {

  @ParameterizedTest(name = "{0} -> {1}")
  @CsvSource({
    "1234567, ***4567",
    "123456789012, ********9012",
    "12345, *2345",
    "1234, ****",
    "12, **",
    "7, *",
  })
  void keepsOnlyTheLastFourCharactersVisible(String account, String expected) {
    assertThat(MaskingUtil.maskAccount(account)).isEqualTo(expected);
  }

  @Test
  void maskAccountHandlesNullAndEmpty() {
    assertThat(MaskingUtil.maskAccount(null)).isNull();
    assertThat(MaskingUtil.maskAccount("")).isEmpty();
  }

  @Test
  void maskAccountPreservesLength() {
    String account = "0012345678";
    assertThat(MaskingUtil.maskAccount(account)).hasSameSizeAs(account);
  }

  @Test
  void masksBothAccountNumbersInACsvRow() {
    String row =
        "ACME-0001,Jane Doe,001,00011,1234567,John Roe,002,00022,987654321012,"
            + "1500000.00,CAD,WIRE,2026-10-15,Invoice 12";

    String masked = MaskingUtil.maskAccountNumbers(row);

    assertThat(masked)
        .isEqualTo(
            "ACME-0001,Jane Doe,001,00011,***4567,John Roe,002,00022,********1012,"
                + "1500000.00,CAD,WIRE,2026-10-15,Invoice 12");
    assertThat(masked).doesNotContain("1234567").doesNotContain("987654321012");
  }

  @Test
  void masksQuotedAndSpaceSeparatedAccounts() {
    assertThat(MaskingUtil.maskAccountNumbers("\"7654321\", 11112222333"))
        .isEqualTo("\"***4321\", *******2333");
  }

  @Test
  void masksDigitRunsLongerThanTwelveSoMalformedAccountsDoNotLeak() {
    assertThat(MaskingUtil.maskAccountNumbers("x,1234567890123,y")).isEqualTo("x,*********0123,y");
  }

  @ParameterizedTest(name = "leaves \"{0}\" unchanged")
  @CsvSource(
      delimiter = '|',
      value = {
        "001,00011,123456",
        "amount 1500000.00",
        "amount .1234567",
        "ref INV-1234567",
        "ref 1234567-A",
        "ref ACME1234567",
        "ref 1234567ACME",
        "under_1234567",
        "2026-10-15",
        "no digits here",
      })
  void leavesShortRunsAmountsDatesAndReferencesAlone(String line) {
    assertThat(MaskingUtil.maskAccountNumbers(line)).isEqualTo(line);
  }

  @Test
  void maskAccountNumbersHandlesNullAndEmpty() {
    assertThat(MaskingUtil.maskAccountNumbers(null)).isNull();
    assertThat(MaskingUtil.maskAccountNumbers("")).isEmpty();
  }

  @Test
  void maskedOutputContainsNoRegexArtifacts() {
    assertThat(MaskingUtil.maskAccountNumbers("$1 \\ 1234567")).isEqualTo("$1 \\ ***4567");
  }
}

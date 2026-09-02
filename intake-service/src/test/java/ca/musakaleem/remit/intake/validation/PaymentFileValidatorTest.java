package ca.musakaleem.remit.intake.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class PaymentFileValidatorTest {

  static final String H = PaymentFileFormat.HEADER;

  static String row(String ref, String amount) {
    return ref
        + ",Jane Doe,001,00011,1234567,John Roe,002,00022,7654321,"
        + amount
        + ",CAD,EFT,2026-10-15,";
  }

  static ScannedFile scan(String content) throws IOException {
    return ScannedFile.scan(new BufferedReader(new StringReader(content)));
  }

  static FileCheck check(String content) throws IOException {
    return PaymentFileValidator.check(scan(content));
  }

  @Test
  void acceptsAWellFormedFile() throws IOException {
    FileCheck result =
        check(
            String.join("\n", H, row("A-1", "10.00"), row("A-2", "20.50"), "TRAILER,2,30.50", ""));

    assertThat(result.isAccepted()).isTrue();
    assertThat(result.rowCount()).isEqualTo(2);
    assertThat(result.declaredTotal()).isEqualByComparingTo("30.50");
    assertThat(result.duplicateRefLines()).isEmpty();
  }

  @Test
  void acceptsCrLfLineEndingsAndAByteOrderMark() throws IOException {
    String content =
        PaymentFileFormat.BYTE_ORDER_MARK
            + H
            + "\r\n"
            + row("A-1", "10.00")
            + "\r\nTRAILER,1,10.00\r\n";

    assertThat(check(content).isAccepted()).isTrue();
  }

  @Test
  void ignoresBlankLinesAfterTheTrailer() throws IOException {
    assertThat(check(H + "\n" + row("A-1", "1.00") + "\nTRAILER,1,1.00\n\n\n").isAccepted())
        .isTrue();
  }

  @Test
  void rejectsAnEmptyFile() throws IOException {
    assertThat(check("").rejection()).isEqualTo(ReasonCode.EMPTY_FILE);
  }

  @Test
  void rejectsAWrongHeader() throws IOException {
    String wrong = H.replace("amount", "amt");

    assertThat(check(wrong + "\n" + row("A-1", "1.00") + "\nTRAILER,1,1.00").rejection())
        .isEqualTo(ReasonCode.INVALID_HEADER);
    assertThat(check(row("A-1", "1.00") + "\nTRAILER,1,1.00").rejection())
        .isEqualTo(ReasonCode.INVALID_HEADER);
  }

  @Test
  void rejectsAMissingTrailer() throws IOException {
    assertThat(check(H + "\n" + row("A-1", "1.00") + "\n" + row("A-2", "1.00")).rejection())
        .isEqualTo(ReasonCode.INVALID_TRAILER);
    assertThat(check(H + "\n").rejection()).isEqualTo(ReasonCode.INVALID_TRAILER);
  }

  @Test
  void rejectsAMalformedTrailer() throws IOException {
    FileCheck result = check(H + "\n" + row("A-1", "1.00") + "\nTRAILER,1,1.0");

    assertThat(result.rejection()).isEqualTo(ReasonCode.INVALID_TRAILER);
    assertThat(result.declaredTotal()).isNull();
  }

  @Test
  void rejectsZeroRows() throws IOException {
    assertThat(check(H + "\nTRAILER,0,0.00\n").rejection()).isEqualTo(ReasonCode.EMPTY_FILE);
  }

  @Test
  void rejectsARecordCountMismatch() throws IOException {
    FileCheck result = check(H + "\n" + row("A-1", "1.00") + "\nTRAILER,2,1.00");

    assertThat(result.rejection()).isEqualTo(ReasonCode.RECORD_COUNT_MISMATCH);
    assertThat(result.detail()).contains("record_count 2").contains("1 rows");
  }

  @Test
  void rejectsAControlTotalMismatch() throws IOException {
    FileCheck result =
        check(H + "\n" + row("A-1", "1.00") + "\n" + row("A-2", "2.00") + "\nTRAILER,2,3.01");

    assertThat(result.rejection()).isEqualTo(ReasonCode.CONTROL_TOTAL_MISMATCH);
    assertThat(result.detail()).contains("3.01").contains("3.00");
    assertThat(result.declaredTotal()).isEqualByComparingTo("3.01");
    assertThat(result.rowCount()).isEqualTo(2);
  }

  @Test
  void controlTotalCountsUnparseableAmountsAsZeroAndKeepsNegatives() {
    List<ScannedFile.Row> rows =
        List.of(
            new ScannedFile.Row(2, row("A-1", "10.00")),
            new ScannedFile.Row(3, row("A-2", "abc")),
            new ScannedFile.Row(4, row("A-3", "-2.50")),
            new ScannedFile.Row(5, "not,a,row"),
            new ScannedFile.Row(6, row("A-4", "0.125")));

    assertThat(PaymentFileValidator.controlTotal(rows))
        .isEqualByComparingTo(new BigDecimal("7.625"));
  }

  @Test
  void findsLaterRowsThatRepeatAnInstructionRef() throws IOException {
    String content =
        String.join(
            "\n",
            H,
            row("A-1", "1.00"),
            row("A-2", "1.00"),
            row("A-1", "1.00"),
            row("", "1.00"),
            row("", "1.00"),
            row("A-1", "1.00"),
            "TRAILER,6,6.00");

    assertThat(check(content).duplicateRefLines()).containsExactlyInAnyOrder(4L, 7L);
  }

  @Test
  void scannerJoinsQuotedLineBreaksAndNumbersRecordsByTheirLastLine() throws IOException {
    String multiLine =
        "A-2,\"Jane\nDoe\",001,00011,1234567,John Roe,002,00022,7654321,2.00,CAD,EFT,2026-10-15,";
    ScannedFile file =
        scan(
            String.join(
                "\n", H, row("A-1", "1.00"), multiLine, row("A-3", "3.00"), "TRAILER,3,6.00"));

    assertThat(file.rows()).extracting(ScannedFile.Row::lineNumber).containsExactly(2L, 4L, 5L);
    assertThat(file.rows().get(1).text()).contains("Jane\nDoe");
    assertThat(file.trailer()).isEqualTo("TRAILER,3,6.00");
    assertThat(PaymentFileValidator.check(file).isAccepted()).isTrue();
  }

  @Test
  void scannerKeepsBlankLinesInsideTheFileAsRows() throws IOException {
    ScannedFile file = scan(String.join("\n", H, row("A-1", "1.00"), "", "TRAILER,2,1.00"));

    assertThat(file.rows()).hasSize(2);
    assertThat(PaymentFileValidator.check(file).isAccepted()).isTrue();
  }
}

package ca.musakaleem.remit.intake.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class PaymentFileFormatTest {

  @Test
  void headerMatchesTheSpecColumnOrder() {
    assertThat(PaymentFileFormat.HEADER)
        .isEqualTo(
            "instruction_ref,debtor_name,debtor_institution,debtor_transit,debtor_account,"
                + "creditor_name,creditor_institution,creditor_transit,creditor_account,amount,"
                + "currency,channel,value_date,remittance_info");
    assertThat(PaymentFileFormat.COLUMN_COUNT).isEqualTo(14);
  }

  @Test
  void splitsAPlainRow() {
    List<String> fields =
        PaymentFileFormat.tokenize(
                "A-1,Jane Doe,001,00011,1234567,John Roe,002,00022,7654321,10.00,CAD,EFT,2026-10-15,")
            .orElseThrow();

    assertThat(fields).hasSize(14);
    assertThat(fields.get(Column.DEBTOR_NAME.ordinal())).isEqualTo("Jane Doe");
    assertThat(fields.get(Column.AMOUNT.ordinal())).isEqualTo("10.00");
    assertThat(fields.get(Column.REMITTANCE_INFO.ordinal())).isEmpty();
  }

  @Test
  void honoursQuotedCommasAndEscapedQuotes() {
    List<String> fields =
        PaymentFileFormat.tokenize(
                "A-1,\"Doe, Jane\",001,00011,1234567,\"Roe \"\"JR\"\" Ltd\",002,00022,7654321,"
                    + "10.00,CAD,EFT,2026-10-15,\"Invoice 1, 2\"")
            .orElseThrow();

    assertThat(fields.get(Column.DEBTOR_NAME.ordinal())).isEqualTo("Doe, Jane");
    assertThat(fields.get(Column.CREDITOR_NAME.ordinal())).isEqualTo("Roe \"JR\" Ltd");
    assertThat(fields.get(Column.REMITTANCE_INFO.ordinal())).isEqualTo("Invoice 1, 2");
  }

  @ParameterizedTest(name = "rejects \"{0}\"")
  @NullAndEmptySource
  @ValueSource(
      strings = {
        "A-1,Jane Doe,001",
        "A-1,Jane Doe,001,00011,1234567,John Roe,002,00022,7654321,10.00,CAD,EFT,2026-10-15,,extra",
        "TRAILER,10,100.00",
      })
  void rejectsRowsWithTheWrongFieldCount(String line) {
    assertThat(PaymentFileFormat.tokenize(line)).isEmpty();
  }

  @Test
  void stripsALeadingByteOrderMarkOnly() {
    assertThat(
            PaymentFileFormat.stripByteOrderMark(
                PaymentFileFormat.BYTE_ORDER_MARK + "instruction_ref"))
        .isEqualTo("instruction_ref");
    assertThat(PaymentFileFormat.stripByteOrderMark("instruction_ref"))
        .isEqualTo("instruction_ref");
    assertThat(PaymentFileFormat.stripByteOrderMark("")).isEmpty();
    assertThat(PaymentFileFormat.stripByteOrderMark(null)).isNull();
  }

  @Test
  void rawInstructionExposesFieldsAndMalformedState() {
    RawInstruction row =
        RawInstruction.parsed(
            2,
            "raw",
            List.of(
                "A-1",
                "n",
                "001",
                "00011",
                "1234567",
                "n",
                "002",
                "00022",
                "7654321",
                "1.00",
                "CAD",
                "EFT",
                "2026-10-15",
                ""));
    RawInstruction bad = RawInstruction.malformed(3, "garbage");

    assertThat(row.malformed()).isFalse();
    assertThat(row.instructionRef()).isEqualTo("A-1");
    assertThat(row.field(Column.CHANNEL)).isEqualTo("EFT");
    assertThat(bad.malformed()).isTrue();
    assertThat(bad.instructionRef()).isNull();
    assertThat(bad.field(Column.AMOUNT)).isNull();
  }
}

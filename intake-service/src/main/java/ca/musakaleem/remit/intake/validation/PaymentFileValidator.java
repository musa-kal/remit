package ca.musakaleem.remit.intake.validation;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * File-level checks on a scanned file (spec 3.4): header, trailer, zero rows, record count and
 * control total. Also finds rows whose instruction_ref repeats an earlier row, so the chunk step
 * can reject them even after a restart.
 */
public final class PaymentFileValidator {

  /** Any plain decimal; amounts that do not parse this way count as zero in the control total. */
  private static final Pattern DECIMAL = Pattern.compile("-?\\d+(\\.\\d+)?");

  private PaymentFileValidator() {}

  /**
   * Runs the checks in a fixed order and stops at the first failure.
   *
   * @param file the scanned file
   * @return accepted, or rejected with the first failed check
   */
  public static FileCheck check(ScannedFile file) {
    if (file.header() == null) {
      return FileCheck.rejected(ReasonCode.EMPTY_FILE, "file has no lines", 0, null);
    }
    int rowCount = file.rows().size();
    if (!PaymentFileFormat.HEADER.equals(file.header())) {
      return FileCheck.rejected(
          ReasonCode.INVALID_HEADER, "first line is not the expected header", rowCount, null);
    }
    Optional<Trailer> parsed = Trailer.parse(file.trailer());
    if (parsed.isEmpty()) {
      return FileCheck.rejected(
          ReasonCode.INVALID_TRAILER,
          "last line is not TRAILER,<record_count>,<control_total>",
          rowCount,
          null);
    }
    Trailer trailer = parsed.get();
    if (rowCount == 0) {
      return FileCheck.rejected(
          ReasonCode.EMPTY_FILE, "file has no instruction rows", 0, trailer.controlTotal());
    }
    if (trailer.recordCount() != rowCount) {
      return FileCheck.rejected(
          ReasonCode.RECORD_COUNT_MISMATCH,
          "trailer record_count " + trailer.recordCount() + " but file has " + rowCount + " rows",
          rowCount,
          trailer.controlTotal());
    }
    BigDecimal computed = controlTotal(file.rows());
    if (!trailer.controlTotalMatches(computed)) {
      return FileCheck.rejected(
          ReasonCode.CONTROL_TOTAL_MISMATCH,
          "trailer control_total "
              + trailer.controlTotal().toPlainString()
              + " but rows sum to "
              + computed.toPlainString(),
          rowCount,
          trailer.controlTotal());
    }
    return FileCheck.accepted(rowCount, trailer.controlTotal(), duplicateRefLines(file.rows()));
  }

  /**
   * Sums the amount column. Rows that cannot be split or whose amount is not a plain decimal
   * contribute zero; such rows are rejected individually later.
   *
   * @param rows instruction records
   * @return the sum
   */
  static BigDecimal controlTotal(List<ScannedFile.Row> rows) {
    BigDecimal total = BigDecimal.ZERO;
    for (ScannedFile.Row row : rows) {
      Optional<List<String>> fields = PaymentFileFormat.tokenize(row.text());
      if (fields.isPresent()) {
        String amount = fields.get().get(Column.AMOUNT.ordinal());
        if (DECIMAL.matcher(amount).matches()) {
          total = total.add(new BigDecimal(amount));
        }
      }
    }
    return total;
  }

  private static Set<Long> duplicateRefLines(List<ScannedFile.Row> rows) {
    Set<String> seen = new HashSet<>();
    Set<Long> duplicates = new LinkedHashSet<>();
    for (ScannedFile.Row row : rows) {
      Optional<List<String>> fields = PaymentFileFormat.tokenize(row.text());
      if (fields.isEmpty()) {
        continue;
      }
      String ref = fields.get().get(Column.INSTRUCTION_REF.ordinal());
      if (!ref.isEmpty() && !seen.add(ref)) {
        duplicates.add(row.lineNumber());
      }
    }
    return duplicates;
  }
}

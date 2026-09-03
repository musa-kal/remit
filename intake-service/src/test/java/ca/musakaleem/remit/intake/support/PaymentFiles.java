package ca.musakaleem.remit.intake.support;

import ca.musakaleem.remit.intake.validation.Column;
import ca.musakaleem.remit.intake.validation.PaymentFileFormat;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** Builds payment file content for tests, with a correct trailer unless told otherwise. */
public final class PaymentFiles {

  /** Value date that is valid while the test clock is pinned to 2026-10-15. */
  public static final String VALUE_DATE = "2026-10-15";

  private PaymentFiles() {}

  /**
   * A valid EFT row.
   *
   * @param ref instruction reference
   * @param amount amount text
   * @return the CSV row
   */
  public static String row(String ref, String amount) {
    return row(ref, "1234567", amount, "CAD", "EFT");
  }

  /**
   * A row with the fields that matter most for routing.
   *
   * @param ref instruction reference
   * @param debtorAccount debtor account
   * @param amount amount text
   * @param currency currency
   * @param channel channel
   * @return the CSV row
   */
  public static String row(
      String ref, String debtorAccount, String amount, String currency, String channel) {
    return String.join(
        ",",
        ref,
        "Test Debtor",
        "001",
        "00011",
        debtorAccount,
        "Test Creditor",
        "002",
        "00022",
        "7654321",
        amount,
        currency,
        channel,
        VALUE_DATE,
        "test");
  }

  /**
   * Generates valid rows spread over the three channels, each within its limit.
   *
   * @param refPrefix prefix for instruction references
   * @param count number of rows
   * @return the rows
   */
  public static List<String> validRows(String refPrefix, int count) {
    String[] channels = {"EFT", "WIRE", "INTERAC"};
    List<String> rows = new ArrayList<>(count);
    for (int i = 1; i <= count; i++) {
      String channel = channels[i % channels.length];
      String account = String.valueOf(1_000_000 + (i % 97));
      String amount = (10 + i % 900) + "." + String.format("%02d", i % 100);
      rows.add(row(refPrefix + "-" + i, account, amount, "CAD", channel));
    }
    return rows;
  }

  /**
   * Header, rows and a correct trailer, newline terminated.
   *
   * @param rows instruction rows
   * @return file content
   */
  public static String file(List<String> rows) {
    return file(rows, trailer(rows));
  }

  /**
   * Header, rows and the given trailer, newline terminated.
   *
   * @param rows instruction rows
   * @param trailer trailer line
   * @return file content
   */
  public static String file(List<String> rows, String trailer) {
    StringBuilder content = new StringBuilder(PaymentFileFormat.HEADER).append('\n');
    rows.forEach(r -> content.append(r).append('\n'));
    return content.append(trailer).append('\n').toString();
  }

  /**
   * A correct trailer for the rows.
   *
   * @param rows instruction rows
   * @return {@code TRAILER,<count>,<total>}
   */
  public static String trailer(List<String> rows) {
    BigDecimal total = BigDecimal.ZERO;
    for (String row : rows) {
      List<String> fields = PaymentFileFormat.tokenize(row).orElseThrow();
      String amount = fields.get(Column.AMOUNT.ordinal());
      if (amount.matches("-?\\d+(\\.\\d+)?")) {
        total = total.add(new BigDecimal(amount));
      }
    }
    return "TRAILER," + rows.size() + "," + total.setScale(2).toPlainString();
  }
}

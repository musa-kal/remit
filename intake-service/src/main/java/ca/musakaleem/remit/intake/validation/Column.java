package ca.musakaleem.remit.intake.validation;

import java.util.Locale;

/** Columns of an instruction row, in the exact order of the file header (spec 3.2). */
public enum Column {
  INSTRUCTION_REF,
  DEBTOR_NAME,
  DEBTOR_INSTITUTION,
  DEBTOR_TRANSIT,
  DEBTOR_ACCOUNT,
  CREDITOR_NAME,
  CREDITOR_INSTITUTION,
  CREDITOR_TRANSIT,
  CREDITOR_ACCOUNT,
  AMOUNT,
  CURRENCY,
  CHANNEL,
  VALUE_DATE,
  REMITTANCE_INFO;

  /**
   * Returns the column name as it appears in the header line.
   *
   * @return lower-case header name, for example {@code debtor_account}
   */
  public String headerName() {
    return name().toLowerCase(Locale.ROOT);
  }
}

package ca.musakaleem.remit.intake.validation;

import java.util.List;

/**
 * One instruction row as read from the file, before validation.
 *
 * @param lineNumber one-based physical line number (the header is line 1)
 * @param rawLine the record exactly as it appeared in the file
 * @param fields the {@link PaymentFileFormat#COLUMN_COUNT} field values, or {@code null} when the
 *     row could not be split into that many fields
 */
public record RawInstruction(long lineNumber, String rawLine, List<String> fields) {

  /**
   * Creates a row whose fields were split successfully.
   *
   * @param lineNumber line number
   * @param rawLine raw text
   * @param fields field values
   * @return the row
   */
  public static RawInstruction parsed(long lineNumber, String rawLine, List<String> fields) {
    return new RawInstruction(lineNumber, rawLine, List.copyOf(fields));
  }

  /**
   * Creates a row that has the wrong number of fields or broken quoting.
   *
   * @param lineNumber line number
   * @param rawLine raw text
   * @return the row
   */
  public static RawInstruction malformed(long lineNumber, String rawLine) {
    return new RawInstruction(lineNumber, rawLine, null);
  }

  /**
   * Tells whether the row could not be split into fields.
   *
   * @return true for a malformed row
   */
  public boolean malformed() {
    return fields == null;
  }

  /**
   * Returns one field value.
   *
   * @param column the column
   * @return the value, or {@code null} for a malformed row
   */
  public String field(Column column) {
    return malformed() ? null : fields.get(column.ordinal());
  }

  /**
   * Returns the row's instruction reference as supplied.
   *
   * @return the reference, or {@code null} for a malformed row
   */
  public String instructionRef() {
    return field(Column.INSTRUCTION_REF);
  }
}

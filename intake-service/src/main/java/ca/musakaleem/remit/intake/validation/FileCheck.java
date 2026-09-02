package ca.musakaleem.remit.intake.validation;

import java.math.BigDecimal;
import java.util.Set;

/**
 * Result of the file-level checks (spec 3.4) on a scanned file.
 *
 * @param rejection the file-level reason code, or {@code null} when the file is accepted
 * @param detail human readable explanation of a rejection, or {@code null}
 * @param rowCount instruction records found between header and trailer
 * @param declaredTotal control total from the trailer, or {@code null} if it could not be read
 * @param duplicateRefLines line numbers of rows whose instruction_ref repeats an earlier row
 */
public record FileCheck(
    ReasonCode rejection,
    String detail,
    int rowCount,
    BigDecimal declaredTotal,
    Set<Long> duplicateRefLines) {

  /** Copies the duplicate line set. */
  public FileCheck {
    duplicateRefLines = duplicateRefLines == null ? Set.of() : Set.copyOf(duplicateRefLines);
  }

  /**
   * Creates an accepted result.
   *
   * @param rowCount rows in the file
   * @param declaredTotal trailer control total
   * @param duplicateRefLines lines with a repeated instruction_ref
   * @return the result
   */
  public static FileCheck accepted(
      int rowCount, BigDecimal declaredTotal, Set<Long> duplicateRefLines) {
    return new FileCheck(null, null, rowCount, declaredTotal, duplicateRefLines);
  }

  /**
   * Creates a rejected result.
   *
   * @param reason file-level reason code
   * @param detail explanation
   * @param rowCount rows found so far
   * @param declaredTotal trailer control total if readable, else {@code null}
   * @return the result
   */
  public static FileCheck rejected(
      ReasonCode reason, String detail, int rowCount, BigDecimal declaredTotal) {
    return new FileCheck(reason, detail, rowCount, declaredTotal, Set.of());
  }

  /**
   * Tells whether the file passed every file-level check.
   *
   * @return true when accepted
   */
  public boolean isAccepted() {
    return rejection == null;
  }
}

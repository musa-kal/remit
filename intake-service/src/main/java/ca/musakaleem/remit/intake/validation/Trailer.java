package ca.musakaleem.remit.intake.validation;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The parsed trailer line, {@code TRAILER,<record_count>,<control_total>} (spec 3.2).
 *
 * @param recordCount declared number of instruction rows
 * @param controlTotal declared sum of all row amounts, exactly two decimals
 */
public record Trailer(int recordCount, BigDecimal controlTotal) {

  private static final Pattern PATTERN = Pattern.compile("TRAILER,(\\d{1,9}),(\\d{1,15}\\.\\d{2})");

  /**
   * Parses a trailer line.
   *
   * @param line candidate trailer line, may be {@code null}
   * @return the trailer, or empty when the line is missing or malformed
   */
  public static Optional<Trailer> parse(String line) {
    if (line == null) {
      return Optional.empty();
    }
    Matcher matcher = PATTERN.matcher(line);
    if (!matcher.matches()) {
      return Optional.empty();
    }
    return Optional.of(
        new Trailer(Integer.parseInt(matcher.group(1)), new BigDecimal(matcher.group(2))));
  }

  /**
   * Checks the declared control total against a computed one, ignoring scale.
   *
   * @param computed sum of the row amounts
   * @return true when the totals are numerically equal
   */
  public boolean controlTotalMatches(BigDecimal computed) {
    return controlTotal.compareTo(computed) == 0;
  }
}

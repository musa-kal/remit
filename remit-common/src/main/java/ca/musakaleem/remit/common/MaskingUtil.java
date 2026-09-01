package ca.musakaleem.remit.common;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Masks account numbers so only the last four characters are visible (FR-INT-07, NFR-01). */
public final class MaskingUtil {

  private static final int VISIBLE_CHARS = 4;
  private static final String MASK = "*";

  /**
   * A standalone run of seven or more digits, that is one not touching a letter, digit, underscore,
   * hyphen or decimal point. Account numbers are 7 to 12 digits; longer runs are masked too so a
   * malformed account never leaks. Amounts such as {@code 1500000.00}, dates and references such as
   * {@code INV-0001234} are left alone.
   */
  private static final Pattern ACCOUNT_LIKE_RUN = Pattern.compile("(?<![\\w.-])\\d{7,}(?![\\w.-])");

  private MaskingUtil() {}

  /**
   * Masks every character except the last four. Values of four characters or fewer are masked
   * completely, because showing them would reveal the whole value.
   *
   * @param value an account number, may be {@code null}
   * @return the masked value with the same length, or {@code null} for {@code null}
   */
  public static String maskAccount(String value) {
    if (value == null) {
      return null;
    }
    int length = value.length();
    if (length <= VISIBLE_CHARS) {
      return MASK.repeat(length);
    }
    return MASK.repeat(length - VISIBLE_CHARS) + value.substring(length - VISIBLE_CHARS);
  }

  /**
   * Masks every standalone run of seven or more digits inside a raw line, for example a CSV row
   * that is logged or published after it failed validation.
   *
   * @param line raw text, may be {@code null}
   * @return the text with account-like digit runs masked, or {@code null} for {@code null}
   */
  public static String maskAccountNumbers(String line) {
    if (line == null) {
      return null;
    }
    Matcher matcher = ACCOUNT_LIKE_RUN.matcher(line);
    StringBuilder masked = new StringBuilder(line.length());
    while (matcher.find()) {
      matcher.appendReplacement(masked, Matcher.quoteReplacement(maskAccount(matcher.group())));
    }
    matcher.appendTail(masked);
    return masked.toString();
  }
}

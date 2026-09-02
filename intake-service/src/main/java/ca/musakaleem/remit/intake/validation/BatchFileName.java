package ca.musakaleem.remit.intake.validation;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A parsed batch file name, {@code PAY_<CLIENTID>_<yyyyMMddHHmmss>.csv} (spec 3.1).
 *
 * @param fileName the full file name
 * @param clientId 3 to 10 upper-case alphanumerics
 * @param createdAt timestamp embedded in the name
 */
public record BatchFileName(String fileName, String clientId, LocalDateTime createdAt) {

  private static final Pattern PATTERN = Pattern.compile("PAY_([A-Z0-9]{3,10})_(\\d{14})\\.csv");
  private static final DateTimeFormatter TIMESTAMP =
      DateTimeFormatter.ofPattern("uuuuMMddHHmmss").withResolverStyle(ResolverStyle.STRICT);

  /**
   * Parses a file name.
   *
   * @param fileName candidate file name, may be {@code null}
   * @return the parsed name, or empty when it does not match the pattern or the timestamp is not a
   *     real date and time
   */
  public static Optional<BatchFileName> parse(String fileName) {
    if (fileName == null) {
      return Optional.empty();
    }
    Matcher matcher = PATTERN.matcher(fileName);
    if (!matcher.matches()) {
      return Optional.empty();
    }
    try {
      LocalDateTime createdAt = LocalDateTime.parse(matcher.group(2), TIMESTAMP);
      return Optional.of(new BatchFileName(fileName, matcher.group(1), createdAt));
    } catch (DateTimeParseException e) {
      return Optional.empty();
    }
  }
}

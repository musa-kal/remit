package ca.musakaleem.remit.intake.validation;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.batch.item.file.transform.DelimitedLineTokenizer;
import org.springframework.batch.item.file.transform.FieldSet;
import org.springframework.batch.item.file.transform.FlatFileFormatException;

/** Layout constants of a payment batch file and RFC 4180 row tokenizing (spec 3.2). */
public final class PaymentFileFormat {

  /** The exact header line every file must start with. */
  public static final String HEADER =
      Arrays.stream(Column.values()).map(Column::headerName).collect(Collectors.joining(","));

  /** Number of fields in an instruction row. */
  public static final int COLUMN_COUNT = Column.values().length;

  /** Byte order mark some editors put in front of UTF-8 files; ignored before the header. */
  public static final char BYTE_ORDER_MARK = (char) 0xFEFF;

  private static final DelimitedLineTokenizer TOKENIZER = newTokenizer();

  private PaymentFileFormat() {}

  /**
   * Splits one CSV record into its fields, honouring double-quoted fields and escaped quotes.
   *
   * @param line one record (may span physical lines when a quoted field contains a line break)
   * @return the unquoted field values, or empty when the record does not have exactly {@link
   *     #COLUMN_COUNT} fields or its quoting is broken
   */
  public static Optional<List<String>> tokenize(String line) {
    if (line == null) {
      return Optional.empty();
    }
    try {
      FieldSet fieldSet = TOKENIZER.tokenize(line);
      return Optional.of(List.of(fieldSet.getValues()));
    } catch (FlatFileFormatException | IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  /**
   * Removes a leading byte order mark, if present.
   *
   * @param line first line of a file
   * @return the line without the mark
   */
  public static String stripByteOrderMark(String line) {
    if (line != null && !line.isEmpty() && line.charAt(0) == BYTE_ORDER_MARK) {
      return line.substring(1);
    }
    return line;
  }

  private static DelimitedLineTokenizer newTokenizer() {
    DelimitedLineTokenizer tokenizer = new DelimitedLineTokenizer(",");
    tokenizer.setQuoteCharacter('"');
    tokenizer.setStrict(true);
    tokenizer.setNames(Arrays.stream(Column.values()).map(Column::name).toArray(String[]::new));
    return tokenizer;
  }
}

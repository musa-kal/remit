package ca.musakaleem.remit.intake.validation;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.batch.item.file.separator.DefaultRecordSeparatorPolicy;
import org.springframework.batch.item.file.separator.RecordSeparatorPolicy;

/**
 * A payment file split into header, instruction records and trailer, used by the file-level checks.
 * Records are assembled with the same {@link DefaultRecordSeparatorPolicy} the item reader uses, so
 * line numbers and row counts agree with what the chunk step later reads.
 *
 * @param header first line without byte order mark, or {@code null} for an empty file
 * @param rows every record between header and trailer
 * @param trailer last non-empty record after the header, or {@code null} when there is none
 */
public record ScannedFile(String header, List<Row> rows, String trailer) {

  /**
   * One record of the file.
   *
   * @param lineNumber physical line number where the record ends (the header is line 1)
   * @param text the record text
   */
  public record Row(long lineNumber, String text) {}

  /**
   * Reads a whole file. Blank lines after the trailer are ignored.
   *
   * @param reader UTF-8 reader positioned at the start of the file
   * @return the scanned file
   * @throws IOException when reading fails, including malformed UTF-8
   */
  public static ScannedFile scan(BufferedReader reader) throws IOException {
    String header = PaymentFileFormat.stripByteOrderMark(reader.readLine());
    if (header == null) {
      return new ScannedFile(null, List.of(), null);
    }
    RecordSeparatorPolicy policy = new DefaultRecordSeparatorPolicy();
    List<Row> records = new ArrayList<>();
    long lineNumber = 1;
    String line;
    while ((line = reader.readLine()) != null) {
      lineNumber++;
      String record = line;
      while (!policy.isEndOfRecord(record) && (line = reader.readLine()) != null) {
        lineNumber++;
        record = policy.preProcess(record) + line;
      }
      records.add(new Row(lineNumber, policy.postProcess(record)));
    }
    while (!records.isEmpty() && records.get(records.size() - 1).text().isEmpty()) {
      records.remove(records.size() - 1);
    }
    if (records.isEmpty()) {
      return new ScannedFile(header, List.of(), null);
    }
    String trailer = records.remove(records.size() - 1).text();
    return new ScannedFile(header, List.copyOf(records), trailer);
  }
}

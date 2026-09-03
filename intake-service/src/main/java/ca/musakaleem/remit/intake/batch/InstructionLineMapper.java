package ca.musakaleem.remit.intake.batch;

import ca.musakaleem.remit.intake.validation.PaymentFileFormat;
import ca.musakaleem.remit.intake.validation.RawInstruction;
import org.springframework.batch.item.file.LineMapper;

/**
 * Maps a CSV record to a {@link RawInstruction}. It never throws: a record with the wrong number of
 * fields becomes a malformed row, which the processor rejects like any other invalid row.
 */
public class InstructionLineMapper implements LineMapper<RawInstruction> {

  @Override
  public RawInstruction mapLine(String line, int lineNumber) {
    return PaymentFileFormat.tokenize(line)
        .map(fields -> RawInstruction.parsed(lineNumber, line, fields))
        .orElseGet(() -> RawInstruction.malformed(lineNumber, line));
  }
}

package ca.musakaleem.remit.intake.batch;

import ca.musakaleem.remit.common.event.PaymentInstruction;
import ca.musakaleem.remit.intake.config.BusinessCalendar;
import ca.musakaleem.remit.intake.validation.InstructionValidationException;
import ca.musakaleem.remit.intake.validation.InstructionValidator;
import ca.musakaleem.remit.intake.validation.RawInstruction;
import ca.musakaleem.remit.intake.validation.ReasonCode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.batch.item.ItemProcessor;

/**
 * Validates one row and turns it into a {@link PaymentInstruction}. Invalid rows throw {@link
 * InstructionValidationException}, which the step's skip policy skips and the skip listener
 * publishes to {@code payments.rejected}.
 */
public class InstructionItemProcessor implements ItemProcessor<RawInstruction, PaymentInstruction> {

  private final InstructionValidator validator;
  private final UUID batchId;
  private final String clientId;
  private final Set<Long> duplicateRefLines;
  private final LocalDate businessDate;

  /**
   * Creates a processor for one step execution. The business date is fixed when the step starts so
   * every row of a file is judged against the same day.
   *
   * @param validator field and channel rules
   * @param clock injected clock
   * @param batchId batch id from the job context
   * @param clientId client id from the job parameters
   * @param duplicateRefLines comma separated lines whose instruction_ref repeats an earlier row
   */
  public InstructionItemProcessor(
      InstructionValidator validator,
      Clock clock,
      UUID batchId,
      String clientId,
      String duplicateRefLines) {
    this.validator = validator;
    this.batchId = batchId;
    this.clientId = clientId;
    this.duplicateRefLines = parseLines(duplicateRefLines);
    this.businessDate = BusinessCalendar.today(clock);
  }

  @Override
  public PaymentInstruction process(RawInstruction row) {
    Set<ReasonCode> reasons = EnumSet.noneOf(ReasonCode.class);
    reasons.addAll(validator.validate(row, businessDate));
    if (duplicateRefLines.contains(row.lineNumber())) {
      reasons.add(ReasonCode.DUPLICATE_INSTRUCTION_REF_IN_FILE);
    }
    if (!reasons.isEmpty()) {
      throw new InstructionValidationException(row.lineNumber(), List.copyOf(reasons));
    }
    return validator.toInstruction(row, batchId, clientId);
  }

  private static Set<Long> parseLines(String lines) {
    if (lines == null || lines.isBlank()) {
      return Set.of();
    }
    return Arrays.stream(lines.split(","))
        .map(String::trim)
        .map(Long::valueOf)
        .collect(Collectors.toUnmodifiableSet());
  }
}

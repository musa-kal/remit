package ca.musakaleem.remit.intake.validation;

import java.util.List;

/** Thrown by the item processor for a row that breaks one or more rules; the step skips it. */
public class InstructionValidationException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final transient List<ReasonCode> reasonCodes;

  /**
   * Creates the exception.
   *
   * @param lineNumber line of the rejected row
   * @param reasonCodes every rule the row failed, at least one
   */
  public InstructionValidationException(long lineNumber, List<ReasonCode> reasonCodes) {
    super("Line " + lineNumber + " rejected: " + reasonCodes);
    if (reasonCodes.isEmpty()) {
      throw new IllegalArgumentException("a rejected row needs at least one reason code");
    }
    this.reasonCodes = List.copyOf(reasonCodes);
  }

  /**
   * Returns the failed rules.
   *
   * @return reason codes in rule order
   */
  public List<ReasonCode> reasonCodes() {
    return reasonCodes;
  }
}

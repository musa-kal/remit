package ca.musakaleem.remit.common.event;

import java.util.List;
import java.util.UUID;

/**
 * A file row that failed validation, published by intake-service to {@code payments.rejected}.
 *
 * @param batchId batch the row belongs to
 * @param clientId client that submitted the file
 * @param lineNumber one-based physical line number in the file (the header is line 1)
 * @param instructionRef the row's instruction_ref as supplied, possibly blank or invalid
 * @param reasonCodes every validation rule the row failed
 * @param rawLine the original CSV line with account-like digit runs masked
 */
public record InstructionRejected(
    UUID batchId,
    String clientId,
    long lineNumber,
    String instructionRef,
    List<String> reasonCodes,
    String rawLine) {}

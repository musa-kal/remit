package ca.musakaleem.remit.api.rejection;

import ca.musakaleem.remit.common.MaskingUtil;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A rejected row in {@code GET /api/v1/batches/{batchId}/rejections}.
 *
 * @param id row id
 * @param batchId batch
 * @param lineNumber file line number, the header being line 1
 * @param instructionRef reference as supplied
 * @param reasonCodes every rule the row failed
 * @param rawLine the CSV line with account numbers masked
 * @param occurredAt when intake-service rejected it
 */
public record RejectionView(
    Long id,
    UUID batchId,
    long lineNumber,
    String instructionRef,
    List<String> reasonCodes,
    String rawLine,
    Instant occurredAt) {

  /**
   * Maps an entity.
   *
   * @param rejection the entity
   * @return the view
   */
  public static RejectionView of(InstructionRejection rejection) {
    return new RejectionView(
        rejection.getId(),
        rejection.getBatchId(),
        rejection.getLineNumber(),
        rejection.getInstructionRef(),
        rejection.getReasonCodes(),
        MaskingUtil.maskAccountNumbers(rejection.getRawLineMasked()),
        rejection.getOccurredAt());
  }
}

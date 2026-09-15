package ca.musakaleem.remit.api.instruction;

import ca.musakaleem.remit.api.security.Callers;
import ca.musakaleem.remit.api.web.NotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Pattern;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/instructions/{idempotencyKey}}: readable by OPERATOR; only an ADMIN sees full
 * account numbers (FR-SEC-05).
 */
@RestController
@RequestMapping("/api/v1/instructions")
@PreAuthorize("hasRole('OPERATOR')")
@Tag(name = "Instructions", description = "Instruction detail and status history")
public class InstructionController {

  private final InstructionRecordRepository instructions;
  private final InstructionStatusHistoryRepository history;

  /**
   * Creates the controller.
   *
   * @param instructions instruction repository
   * @param history status history repository
   */
  public InstructionController(
      InstructionRecordRepository instructions, InstructionStatusHistoryRepository history) {
    this.instructions = instructions;
    this.history = history;
  }

  /**
   * One instruction with its status history.
   *
   * @param idempotencyKey 64 hex characters
   * @param authentication the caller; ADMIN gets unmasked account numbers
   * @return the instruction
   */
  @GetMapping("/{idempotencyKey}")
  @Transactional(readOnly = true)
  @Operation(
      summary = "Instruction detail and status history",
      description = "Account numbers are masked to the last four digits unless the caller is ADMIN")
  public InstructionDetailView get(
      @PathVariable
          @Pattern(regexp = "[0-9a-f]{64}", message = "must be 64 lower-case hex characters")
          String idempotencyKey,
      Authentication authentication) {
    InstructionRecord instruction =
        instructions
            .findById(idempotencyKey)
            .orElseThrow(
                () -> new NotFoundException("Instruction " + idempotencyKey + " not found"));
    return InstructionDetailView.of(
        instruction,
        history.findByIdempotencyKeyOrderByOccurredAtAscIdAsc(idempotencyKey),
        Callers.isAdmin(authentication));
  }
}

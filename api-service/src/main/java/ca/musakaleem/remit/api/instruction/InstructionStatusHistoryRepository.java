package ca.musakaleem.remit.api.instruction;

import ca.musakaleem.remit.common.event.InstructionStatus;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Status history of instructions. */
public interface InstructionStatusHistoryRepository
    extends JpaRepository<InstructionStatusHistory, Long> {

  /**
   * Tells whether an event was stored already.
   *
   * @param eventId the event id as text
   * @return true when stored
   */
  boolean existsByEventId(String eventId);

  /**
   * The history of one instruction, oldest first.
   *
   * @param idempotencyKey instruction key
   * @return the entries
   */
  List<InstructionStatusHistory> findByIdempotencyKeyOrderByOccurredAtAscIdAsc(
      String idempotencyKey);

  /**
   * Counts events of one status in a range.
   *
   * @param status the status
   * @param from earliest {@code occurred_at}, inclusive, or {@code null}
   * @param to latest {@code occurred_at}, exclusive, or {@code null}
   * @return the count
   */
  @Query(
      """
      select count(h) from InstructionStatusHistory h
      where h.status = :status
        and (:from is null or h.occurredAt >= :from) and (:to is null or h.occurredAt < :to)
      """)
  long countByStatusBetween(
      @Param("status") InstructionStatus status,
      @Param("from") Instant from,
      @Param("to") Instant to);
}

package ca.musakaleem.remit.api.rejection;

import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Rejected rows. */
public interface InstructionRejectionRepository extends JpaRepository<InstructionRejection, Long> {

  /**
   * Tells whether a line of a batch was stored already (the dedupe key).
   *
   * @param batchId batch id as text
   * @param lineNumber file line number
   * @return true when stored
   */
  boolean existsByBatchIdAndLineNumber(String batchId, long lineNumber);

  /**
   * The rejected rows of a batch.
   *
   * @param batchId batch id as text
   * @param pageable page and sort
   * @return the page
   */
  Page<InstructionRejection> findByBatchId(String batchId, Pageable pageable);

  /**
   * Counts the rejected rows of a batch.
   *
   * @param batchId batch id as text
   * @return the count
   */
  long countByBatchId(String batchId);

  /**
   * Counts rejected rows in a range.
   *
   * @param from earliest {@code occurred_at}, inclusive, or {@code null}
   * @param to latest {@code occurred_at}, exclusive, or {@code null}
   * @return the count
   */
  @Query(
      """
      select count(r) from InstructionRejection r
      where (:from is null or r.occurredAt >= :from) and (:to is null or r.occurredAt < :to)
      """)
  long countBetween(@Param("from") Instant from, @Param("to") Instant to);
}

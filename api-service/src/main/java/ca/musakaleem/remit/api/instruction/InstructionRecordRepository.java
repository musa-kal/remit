package ca.musakaleem.remit.api.instruction;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Instruction read model, keyed by idempotency key. */
public interface InstructionRecordRepository
    extends JpaRepository<InstructionRecord, String>, JpaSpecificationExecutor<InstructionRecord> {

  /**
   * Counts the instructions of one batch per status.
   *
   * @param batchId batch id as text
   * @return rows of status and count
   */
  @Query(
      """
      select i.status, count(i) from InstructionRecord i
      where i.batchId = :batchId group by i.status
      """)
  List<Object[]> countByStatusForBatch(@Param("batchId") String batchId);

  /**
   * Counts instructions per status whose last status change falls in a range.
   *
   * @param from earliest {@code updated_at}, inclusive, or {@code null}
   * @param to latest {@code updated_at}, exclusive, or {@code null}
   * @return rows of status and count
   */
  @Query(
      """
      select i.status, count(i) from InstructionRecord i
      where (:from is null or i.updatedAt >= :from) and (:to is null or i.updatedAt < :to)
      group by i.status
      """)
  List<Object[]> countByStatus(@Param("from") Instant from, @Param("to") Instant to);
}

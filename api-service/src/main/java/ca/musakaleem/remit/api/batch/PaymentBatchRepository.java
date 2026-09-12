package ca.musakaleem.remit.api.batch;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Batch read model; the id is the batch UUID as text. */
public interface PaymentBatchRepository
    extends JpaRepository<PaymentBatch, String>, JpaSpecificationExecutor<PaymentBatch> {

  /**
   * Counts batches per status received in a range.
   *
   * @param from earliest {@code received_at}, inclusive, or {@code null}
   * @param to latest {@code received_at}, exclusive, or {@code null}
   * @return rows of status and count
   */
  @Query(
      """
      select b.status, count(b) from PaymentBatch b
      where (:from is null or b.receivedAt >= :from) and (:to is null or b.receivedAt < :to)
      group by b.status
      """)
  List<Object[]> countByStatus(@Param("from") Instant from, @Param("to") Instant to);
}

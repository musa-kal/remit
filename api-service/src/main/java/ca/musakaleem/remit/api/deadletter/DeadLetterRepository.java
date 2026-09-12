package ca.musakaleem.remit.api.deadletter;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Dead letters. Rows that are about to change are read with a row lock. */
public interface DeadLetterRepository
    extends JpaRepository<DeadLetter, Long>, JpaSpecificationExecutor<DeadLetter> {

  /**
   * Finds the row of a failed source record (the dedupe key).
   *
   * @param originalTopic source topic
   * @param originalPartition source partition
   * @param originalOffset source offset
   * @return the row, if seen before
   */
  Optional<DeadLetter> findByOriginalTopicAndOriginalPartitionAndOriginalOffset(
      String originalTopic, int originalPartition, long originalOffset);

  /**
   * Loads a row for update, so two concurrent replays of it are serialized.
   *
   * @param id dead letter id
   * @return the locked row
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select d from DeadLetter d where d.id = :id")
  Optional<DeadLetter> findByIdForUpdate(@Param("id") long id);

  /**
   * Loads the rows of an instruction in the given states for update, newest first.
   *
   * @param idempotencyKey instruction key
   * @param statuses states to look for
   * @return the locked rows
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  List<DeadLetter> findByIdempotencyKeyAndStatusInOrderByIdDesc(
      String idempotencyKey, Collection<DeadLetterStatus> statuses);

  /**
   * Counts the dead letters of a batch.
   *
   * @param batchId batch id as text
   * @return the count
   */
  @Query("select count(d) from DeadLetter d where d.batchId = :batchId")
  long countByBatch(@Param("batchId") String batchId);

  /**
   * Counts dead letters per status first seen in a range.
   *
   * @param from earliest {@code first_seen_at}, inclusive, or {@code null}
   * @param to latest {@code first_seen_at}, exclusive, or {@code null}
   * @return rows of status and count
   */
  @Query(
      """
      select d.status, count(d) from DeadLetter d
      where (:from is null or d.firstSeenAt >= :from) and (:to is null or d.firstSeenAt < :to)
      group by d.status
      """)
  List<Object[]> countByStatus(@Param("from") Instant from, @Param("to") Instant to);
}

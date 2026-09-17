package ca.musakaleem.remit.api.instruction;

import ca.musakaleem.remit.api.messaging.EventTime;
import ca.musakaleem.remit.common.event.Channel;
import ca.musakaleem.remit.common.event.InstructionStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Instruction read model, keyed by idempotency key. Three listeners (status events, routed records,
 * dead letters) can see a new key at the same moment, so rows are created with {@link #claim}: an
 * insert that tolerates an existing row, then a locked read. The second listener waits for the
 * first to commit instead of failing on the primary key.
 */
public interface InstructionRecordRepository
    extends JpaRepository<InstructionRecord, String>, JpaSpecificationExecutor<InstructionRecord> {

  /**
   * Inserts a row with only its key, batch and first status unless the key exists.
   *
   * @param key idempotency key
   * @param batchId batch id as text
   * @param status first status
   * @param channel channel name, may be {@code null}
   * @param updatedAt time of the status
   * @return the affected row count reported by the driver; not used to decide anything
   */
  @Modifying(flushAutomatically = true)
  @Query(
      nativeQuery = true,
      value =
          """
          INSERT INTO payment_instruction
            (idempotency_key, batch_id, status, channel, replay_count, updated_at)
          VALUES (:key, :batchId, :status, :channel, 0, :updatedAt)
          ON DUPLICATE KEY UPDATE idempotency_key = idempotency_key
          """)
  int insertIfAbsent(
      @Param("key") String key,
      @Param("batchId") String batchId,
      @Param("status") String status,
      @Param("channel") String channel,
      @Param("updatedAt") Instant updatedAt);

  /**
   * Loads a row with a write lock.
   *
   * @param key idempotency key
   * @return the locked row
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select i from InstructionRecord i where i.idempotencyKey = :key")
  Optional<InstructionRecord> findForUpdate(@Param("key") String key);

  /**
   * Returns the row of a key, locked, creating it with the given status if it does not exist.
   *
   * @param key idempotency key
   * @param batchId batch the instruction arrived in
   * @param status status of a new row
   * @param channel channel of a new row, may be {@code null}
   * @param occurredAt time of that status
   * @return the locked row
   */
  default InstructionRecord claim(
      String key, UUID batchId, InstructionStatus status, Channel channel, Instant occurredAt) {
    insertIfAbsent(
        key,
        batchId.toString(),
        status.name(),
        channel == null ? null : channel.name(),
        EventTime.millis(occurredAt));
    return findForUpdate(key).orElseThrow(() -> new IllegalStateException("Claimed row vanished"));
  }

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

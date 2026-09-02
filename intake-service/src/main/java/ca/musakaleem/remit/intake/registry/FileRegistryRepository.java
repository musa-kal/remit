package ca.musakaleem.remit.intake.registry;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository for the file registry. */
public interface FileRegistryRepository extends JpaRepository<FileRegistryEntry, Long> {

  /**
   * Tells whether a file with this content was already received.
   *
   * @param fileHash SHA-256 hex
   * @return true for a duplicate file
   */
  boolean existsByFileHash(String fileHash);

  /**
   * Finds the row of a batch.
   *
   * @param batchId batch id as a 36 character string
   * @return the row, if any
   */
  Optional<FileRegistryEntry> findByBatchId(String batchId);

  /**
   * Finds the row of a file by content hash.
   *
   * @param fileHash SHA-256 hex
   * @return the row, if any
   */
  Optional<FileRegistryEntry> findByFileHash(String fileHash);
}

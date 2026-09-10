package ca.musakaleem.remit.api.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * The audit trail's storage (FR-API-02). It can append and read, nothing else: there is
 * deliberately no update or delete operation, and the MongoDB user of the compose stack is not
 * allowed to perform one either.
 */
public interface AuditEventStore {

  /**
   * Appends a document.
   *
   * @param event the document, without an id
   * @return true when stored, false when a document with the same {@code eventId} already exists
   */
  boolean insert(AuditEvent event);

  /**
   * Reads documents, newest first unless the page asks for another order.
   *
   * @param query filters
   * @param pageable page number and size
   * @return the page
   */
  Page<AuditEvent> find(AuditQuery query, Pageable pageable);
}

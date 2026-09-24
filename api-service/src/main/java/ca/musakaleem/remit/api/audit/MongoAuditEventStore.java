package ca.musakaleem.remit.api.audit;

import java.util.ArrayList;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

/**
 * {@link AuditEventStore} on MongoDB. Writes use {@link MongoTemplate#insert(Object)}, never {@code
 * save}, because save would be an upsert and the compose user may only insert.
 */
@Repository
public class MongoAuditEventStore implements AuditEventStore {

  private static final String OCCURRED_AT = "occurredAt";
  private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, OCCURRED_AT);

  private final MongoTemplate mongo;

  /**
   * Creates the store.
   *
   * @param mongo template bound to {@code remit_audit}
   */
  public MongoAuditEventStore(MongoTemplate mongo) {
    this.mongo = mongo;
  }

  @Override
  public boolean insert(AuditEvent event) {
    try {
      mongo.insert(event, AuditEvent.COLLECTION);
      return true;
    } catch (DuplicateKeyException alreadyWritten) {
      // The unique eventId index turned a redelivered event into a no-op.
      return false;
    }
  }

  @Override
  public Page<AuditEvent> find(AuditQuery query, Pageable pageable) {
    long total = mongo.count(filter(query), AuditEvent.class, AuditEvent.COLLECTION);
    Sort sort = pageable.getSort().isSorted() ? pageable.getSort() : NEWEST_FIRST;
    Query page = filter(query).with(sort).skip(pageable.getOffset()).limit(pageable.getPageSize());
    return new PageImpl<>(
        mongo.find(page, AuditEvent.class, AuditEvent.COLLECTION), pageable, total);
  }

  private static Query filter(AuditQuery query) {
    List<Criteria> conditions = new ArrayList<>();
    if (query.entityId() != null) {
      conditions.add(Criteria.where("entityId").is(query.entityId()));
    }
    if (query.eventType() != null) {
      conditions.add(Criteria.where("eventType").is(query.eventType().name()));
    }
    if (query.from() != null) {
      conditions.add(Criteria.where(OCCURRED_AT).gte(query.from()));
    }
    if (query.to() != null) {
      conditions.add(Criteria.where(OCCURRED_AT).lt(query.to()));
    }
    return conditions.isEmpty()
        ? new Query()
        : new Query(new Criteria().andOperator(conditions.toArray(Criteria[]::new)));
  }
}

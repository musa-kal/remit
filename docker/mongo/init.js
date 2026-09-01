// Runs once, on first start with an empty data volume (docker-entrypoint-initdb.d).
// Creates the remit_audit database, an append-only application user, and the
// audit_events collection with the indexes from spec section 8.2.

const auditDb = db.getSiblingDB('remit_audit');
const appUser = process.env.REMIT_MONGO_USER;
const appPassword = process.env.REMIT_MONGO_PASSWORD;

if (!appUser || !appPassword) {
  throw new Error('REMIT_MONGO_USER and REMIT_MONGO_PASSWORD must be set');
}

// Audit events are immutable: the application role can insert and read, never update or delete.
auditDb.createRole({
  role: 'auditAppendOnly',
  privileges: [
    {
      resource: { db: 'remit_audit', collection: 'audit_events' },
      actions: ['find', 'insert', 'listIndexes'],
    },
    {
      resource: { db: 'remit_audit', collection: '' },
      actions: ['listCollections'],
    },
  ],
  roles: [],
});

auditDb.createUser({
  user: appUser,
  pwd: appPassword,
  roles: [{ role: 'auditAppendOnly', db: 'remit_audit' }],
});

auditDb.createCollection('audit_events');
const auditEvents = auditDb.getCollection('audit_events');

// Unique eventId makes audit writes idempotent when an event is consumed twice.
auditEvents.createIndex({ eventId: 1 }, { name: 'ux_audit_event_id', unique: true });
auditEvents.createIndex({ entityId: 1, occurredAt: -1 }, { name: 'ix_audit_entity_occurred' });
auditEvents.createIndex({ eventType: 1, occurredAt: -1 }, { name: 'ix_audit_type_occurred' });

print('remit: created remit_audit user, audit_events collection and indexes');

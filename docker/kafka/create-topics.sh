#!/usr/bin/env bash
# Creates every Remit topic (spec section 5) on a fresh broker, then exits 0.
# Run by the kafka-init compose service; safe to re-run thanks to --if-not-exists.
set -euo pipefail

BOOTSTRAP="${KAFKA_BOOTSTRAP:-kafka:9092}"
PARTITIONS="${TOPIC_PARTITIONS:-3}"
REPLICATION="${TOPIC_REPLICATION_FACTOR:-1}"
KAFKA_TOPICS=/opt/kafka/bin/kafka-topics.sh

TOPICS=(
  payments.instructions
  payments.rejected
  payments.routed.eft
  payments.routed.wire
  payments.routed.interac
  payments.status-events
  payments.batch-events
  payments.instructions.DLT
)

for topic in "${TOPICS[@]}"; do
  "${KAFKA_TOPICS}" --bootstrap-server "${BOOTSTRAP}" --create --if-not-exists \
    --topic "${topic}" --partitions "${PARTITIONS}" --replication-factor "${REPLICATION}"
done

echo "remit: topics on ${BOOTSTRAP}:"
"${KAFKA_TOPICS}" --bootstrap-server "${BOOTSTRAP}" --list

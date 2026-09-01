#!/usr/bin/env bash
# Creates one least-privilege MySQL user per service, each granted only on its own schema.
# Runs once from docker-entrypoint-initdb.d after init.sql. Passwords come from .env via compose.
# The body runs in a subshell so it is safe whether the entrypoint executes or sources it.

create_service_users() (
  set -euo pipefail

  : "${MYSQL_ROOT_PASSWORD:?MYSQL_ROOT_PASSWORD is required}"
  : "${REMIT_INTAKE_DB_PASSWORD:?REMIT_INTAKE_DB_PASSWORD is required}"
  : "${REMIT_ROUTING_DB_PASSWORD:?REMIT_ROUTING_DB_PASSWORD is required}"
  : "${REMIT_API_DB_PASSWORD:?REMIT_API_DB_PASSWORD is required}"

  MYSQL_PWD="${MYSQL_ROOT_PASSWORD}" mysql --protocol=socket -uroot <<SQL
CREATE USER IF NOT EXISTS 'remit_intake'@'%' IDENTIFIED BY '${REMIT_INTAKE_DB_PASSWORD}';
GRANT ALL PRIVILEGES ON remit_intake.* TO 'remit_intake'@'%';

CREATE USER IF NOT EXISTS 'remit_routing'@'%' IDENTIFIED BY '${REMIT_ROUTING_DB_PASSWORD}';
GRANT ALL PRIVILEGES ON remit_routing.* TO 'remit_routing'@'%';

CREATE USER IF NOT EXISTS 'remit_api'@'%' IDENTIFIED BY '${REMIT_API_DB_PASSWORD}';
GRANT ALL PRIVILEGES ON remit_api.* TO 'remit_api'@'%';

FLUSH PRIVILEGES;
SQL

  echo "remit: created service users remit_intake, remit_routing, remit_api"
)

create_service_users

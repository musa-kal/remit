-- Runs once, on first start with an empty data volume (docker-entrypoint-initdb.d).
-- One schema per service; no service reads another service's schema (ADR 0001).
-- Service users and grants are created by init-users.sh, which reads passwords from .env.

CREATE DATABASE IF NOT EXISTS remit_intake
  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE DATABASE IF NOT EXISTS remit_routing
  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE DATABASE IF NOT EXISTS remit_api
  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

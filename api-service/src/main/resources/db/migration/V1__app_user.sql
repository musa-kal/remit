-- Operator accounts of api-service (FR-SEC-02). Passwords are BCrypt hashes, never plain text.
-- Users are seeded by UserSeeder in the local and docker profiles from environment variables,
-- so no password or hash is ever committed.
CREATE TABLE app_user (
  id            BIGINT        NOT NULL AUTO_INCREMENT,
  username      VARCHAR(50)   NOT NULL,
  password_hash VARCHAR(100)  NOT NULL,
  enabled       BOOLEAN       NOT NULL,
  created_at    TIMESTAMP(3)  NOT NULL,
  CONSTRAINT pk_app_user PRIMARY KEY (id),
  CONSTRAINT ux_app_user_username UNIQUE (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- One row per role a user holds: OPERATOR, ADMIN.
CREATE TABLE app_user_role (
  user_id BIGINT      NOT NULL,
  role    VARCHAR(20) NOT NULL,
  CONSTRAINT pk_app_user_role PRIMARY KEY (user_id, role),
  CONSTRAINT fk_app_user_role_user FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

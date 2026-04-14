-- Application users table for BCrypt-authenticated login.
-- Replaces the hardcoded in-memory map in AuthController.
-- Roles: ROLE_ADMIN (full access), ROLE_USER (read+write), ROLE_READER (read-only).
CREATE TABLE app_user (
    id          BIGSERIAL    PRIMARY KEY,
    username    VARCHAR(50)  NOT NULL UNIQUE,
    password    VARCHAR(255) NOT NULL,   -- BCrypt hash (strength 10)
    role        VARCHAR(20)  NOT NULL DEFAULT 'ROLE_READER',
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

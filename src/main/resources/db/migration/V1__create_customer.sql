-- =============================================================================
-- V1 — Create the customer table
--
-- This is the baseline schema migration.
-- Flyway applies migrations in version order (V1, V2, ...) at application startup.
-- Once applied, a migration file must NEVER be modified — Flyway checksums each
-- file and fails startup if a checksum mismatch is detected.
--
-- Design notes:
--   - BIGSERIAL: PostgreSQL auto-increment, equivalent to BIGINT GENERATED ALWAYS AS IDENTITY.
--     Maps to JPA @GeneratedValue(strategy = GenerationType.IDENTITY) in Customer.java.
--   - NOT NULL constraints: enforced at the DB level in addition to Bean Validation
--     in CreateCustomerRequest.java (@NotBlank) for defense in depth.
--   - IF NOT EXISTS: idempotent — safe to run on a database where the table already exists
--     (e.g., after a failed migration attempt that was partially applied).
-- =============================================================================

CREATE TABLE IF NOT EXISTS customer (
    id    BIGSERIAL    PRIMARY KEY,
    name  VARCHAR(255) NOT NULL,
    email VARCHAR(255) NOT NULL
);

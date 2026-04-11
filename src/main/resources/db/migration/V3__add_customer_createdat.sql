-- =============================================================================
-- V3 — Add created_at column to the customer table
--
-- This column records when each customer was created. It is used by the v2
-- API to return creation timestamps in the response (CustomerDtoV2).
--
-- Design decisions:
--   - TIMESTAMP WITH TIME ZONE (TIMESTAMPTZ) stores the absolute point in time
--     regardless of the server timezone, which is safer for distributed systems.
--   - DEFAULT NOW() back-fills all existing rows automatically, so no data
--     migration is needed and the application can be deployed without downtime.
--   - NOT NULL is safe here because the DEFAULT guarantees every row has a value.
--
-- Immutability rule: Flyway migrations must never be edited after they are
-- applied to any environment. Add a new migration (V4__...) for further changes.
-- =============================================================================

ALTER TABLE customer
    ADD COLUMN created_at TIMESTAMPTZ NOT NULL DEFAULT NOW();

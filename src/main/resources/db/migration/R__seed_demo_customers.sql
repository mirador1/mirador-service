-- =============================================================================
-- R__seed_demo_customers — Repeatable Flyway migration for demo seed data
--
-- Repeatable migrations (R__ prefix) differ from versioned migrations (V__ prefix):
--   - They have no version number — order within the run group is alphabetical by name.
--   - They re-run whenever the file checksum changes (i.e., when you edit this file).
--   - They run AFTER all versioned migrations on each startup where the checksum changed.
--
-- Use case: local dev / CI seed data that should be trivial to reset or extend.
-- Editing this file (e.g., adding a new row) triggers a re-run on the next startup.
-- Production environments should not use seed migrations with real data.
--
-- Idempotency: WHERE NOT EXISTS guards ensure re-runs are safe and do not
-- create duplicate rows even when there is no UNIQUE constraint on the column.
-- =============================================================================

INSERT INTO customer (name, email)
SELECT 'Alice Demo', 'alice@demo.com'
WHERE NOT EXISTS (SELECT 1 FROM customer WHERE email = 'alice@demo.com');

INSERT INTO customer (name, email)
SELECT 'Bob Demo', 'bob@demo.com'
WHERE NOT EXISTS (SELECT 1 FROM customer WHERE email = 'bob@demo.com');

INSERT INTO customer (name, email)
SELECT 'Charlie Demo', 'charlie@demo.com'
WHERE NOT EXISTS (SELECT 1 FROM customer WHERE email = 'charlie@demo.com');

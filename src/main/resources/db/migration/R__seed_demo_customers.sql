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

INSERT INTO customer (name, email)
SELECT 'Diana Martinez', 'diana.martinez@demo.com'
WHERE NOT EXISTS (SELECT 1 FROM customer WHERE email = 'diana.martinez@demo.com');

INSERT INTO customer (name, email)
SELECT 'Étienne Dupont', 'etienne.dupont@demo.com'
WHERE NOT EXISTS (SELECT 1 FROM customer WHERE email = 'etienne.dupont@demo.com');

INSERT INTO customer (name, email)
SELECT 'Fatima Al-Rashid', 'fatima.alrashid@demo.com'
WHERE NOT EXISTS (SELECT 1 FROM customer WHERE email = 'fatima.alrashid@demo.com');

INSERT INTO customer (name, email)
SELECT 'George Kim', 'george.kim@demo.com'
WHERE NOT EXISTS (SELECT 1 FROM customer WHERE email = 'george.kim@demo.com');

INSERT INTO customer (name, email)
SELECT 'Hana Tanaka', 'hana.tanaka@demo.com'
WHERE NOT EXISTS (SELECT 1 FROM customer WHERE email = 'hana.tanaka@demo.com');

INSERT INTO customer (name, email)
SELECT 'Ivan Petrov', 'ivan.petrov@demo.com'
WHERE NOT EXISTS (SELECT 1 FROM customer WHERE email = 'ivan.petrov@demo.com');

INSERT INTO customer (name, email)
SELECT 'Julia Santos', 'julia.santos@demo.com'
WHERE NOT EXISTS (SELECT 1 FROM customer WHERE email = 'julia.santos@demo.com');

INSERT INTO customer (name, email)
SELECT 'Kevin O''Brien', 'kevin.obrien@demo.com'
WHERE NOT EXISTS (SELECT 1 FROM customer WHERE email = 'kevin.obrien@demo.com');

INSERT INTO customer (name, email)
SELECT 'Laura Fischer', 'laura.fischer@demo.com'
WHERE NOT EXISTS (SELECT 1 FROM customer WHERE email = 'laura.fischer@demo.com');

INSERT INTO customer (name, email)
SELECT 'Marco Rossi', 'marco.rossi@demo.com'
WHERE NOT EXISTS (SELECT 1 FROM customer WHERE email = 'marco.rossi@demo.com');

INSERT INTO customer (name, email)
SELECT 'Nadia Kowalski', 'nadia.kowalski@demo.com'
WHERE NOT EXISTS (SELECT 1 FROM customer WHERE email = 'nadia.kowalski@demo.com');

INSERT INTO customer (name, email)
SELECT 'Oscar Lindgren', 'oscar.lindgren@demo.com'
WHERE NOT EXISTS (SELECT 1 FROM customer WHERE email = 'oscar.lindgren@demo.com');

INSERT INTO customer (name, email)
SELECT 'Priya Sharma', 'priya.sharma@demo.com'
WHERE NOT EXISTS (SELECT 1 FROM customer WHERE email = 'priya.sharma@demo.com');

INSERT INTO customer (name, email)
SELECT 'Quentin Moreau', 'quentin.moreau@demo.com'
WHERE NOT EXISTS (SELECT 1 FROM customer WHERE email = 'quentin.moreau@demo.com');

INSERT INTO customer (name, email)
SELECT 'Rita Chen', 'rita.chen@demo.com'
WHERE NOT EXISTS (SELECT 1 FROM customer WHERE email = 'rita.chen@demo.com');

INSERT INTO customer (name, email)
SELECT 'Stefan Gruber', 'stefan.gruber@demo.com'
WHERE NOT EXISTS (SELECT 1 FROM customer WHERE email = 'stefan.gruber@demo.com');

INSERT INTO customer (name, email)
SELECT 'Tara Johnson', 'tara.johnson@demo.com'
WHERE NOT EXISTS (SELECT 1 FROM customer WHERE email = 'tara.johnson@demo.com');

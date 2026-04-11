-- =============================================================================
-- V2 — Create the ShedLock distributed scheduler lock table
--
-- ShedLock uses this table to coordinate scheduled task execution across
-- multiple application instances (e.g., in Kubernetes deployments).
-- Only the instance that successfully INSERTs/UPDATEs this row runs the task.
--
-- Column meanings:
--   name       — unique task identifier (matches @SchedulerLock(name = "..."))
--   lock_until — timestamp until which the lock is held; other instances wait
--                until this time before attempting to acquire the lock
--   locked_at  — timestamp when the lock was last acquired (for debugging)
--   locked_by  — hostname of the instance that holds the lock (for debugging)
--
-- The PRIMARY KEY on (name) ensures only one row per task name, which is the
-- basis of the mutual exclusion: an INSERT fails if a lock is already held.
--
-- This table is managed by ShedLock, not by Hibernate. It must exist BEFORE
-- the application starts — hence the Flyway migration.
-- Configuration: ShedLockConfig.java, CustomerStatsScheduler.java
-- =============================================================================

CREATE TABLE shedlock (
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);

-- =============================================================================
-- V4 — Create the audit_event table for security audit logging
--
-- Records all security-relevant actions: login attempts, customer CRUD operations,
-- API key usage, etc. Queryable via pgAdmin or a future /audit endpoint.
--
-- Design:
--   - user_name: the authenticated principal (JWT subject, API key, or "anonymous")
--   - action: the operation performed (LOGIN_SUCCESS, LOGIN_FAILED, CUSTOMER_CREATED, etc.)
--   - detail: free-form context (e.g., customer ID, error reason)
--   - ip_address: client IP (from X-Forwarded-For or direct connection)
--   - created_at: timestamp set by DB default NOW()
-- =============================================================================

CREATE TABLE IF NOT EXISTS audit_event (
    id         BIGSERIAL    PRIMARY KEY,
    user_name  VARCHAR(255) NOT NULL,
    action     VARCHAR(100) NOT NULL,
    detail     TEXT,
    ip_address VARCHAR(45),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_audit_event_user ON audit_event (user_name);
CREATE INDEX idx_audit_event_action ON audit_event (action);
CREATE INDEX idx_audit_event_created ON audit_event (created_at);

-- =============================================================================
-- V8 — Create the orders table
--
-- Sibling : V7 (product) + V9 (order_line). Independent of V7 — foundation
-- only references existing customer (V1).
--
-- Note on table name : "orders" (plural) NOT "order" — `order` is a SQL
-- reserved word in PostgreSQL (ORDER BY clause). Using the plural avoids
-- the need to quote the identifier everywhere.
--
-- Design notes :
--   - customer_id REFERENCES customer ON DELETE RESTRICT : refuse deleting
--     a customer that still has orders (audit-trail preservation).
--   - status ENUM via CHECK constraint : keeps it simple ; don't introduce
--     a separate Postgres ENUM type (migration headache when adding values).
--   - total_amount NUMERIC(12,2) : computed by the application from
--     OrderLines, stored denormalised for query speed (avoids JOIN +
--     SUM() on every list view). Recomputed on every line add/remove
--     in a transaction.
--   - created_at + updated_at TIMESTAMPTZ : audit columns.
-- =============================================================================

CREATE TABLE IF NOT EXISTS orders (
    id              BIGSERIAL       PRIMARY KEY,
    customer_id     BIGINT          NOT NULL REFERENCES customer(id) ON DELETE RESTRICT,
    status          VARCHAR(20)     NOT NULL DEFAULT 'PENDING'
                                    CHECK (status IN ('PENDING', 'CONFIRMED', 'SHIPPED', 'CANCELLED')),
    total_amount    NUMERIC(12, 2)  NOT NULL DEFAULT 0 CHECK (total_amount >= 0),
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_orders_customer_id ON orders(customer_id);
CREATE INDEX IF NOT EXISTS idx_orders_status      ON orders(status);

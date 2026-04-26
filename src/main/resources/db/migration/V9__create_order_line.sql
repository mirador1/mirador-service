-- =============================================================================
-- V9 — Create the order_line table
--
-- Final entity of the e-commerce surface backlog (TASKS.md 2026-04-26).
-- Sibling : V7 (product) + V8 (orders). Requires both V7 and V8 to be applied
-- first (Flyway enforces version order at startup).
--
-- Why an entity (not a join table) : carries quantity + price snapshot at the
-- moment of order + individual lifecycle (line can be cancelled / refunded
-- independently of its parent order). See ADR (forthcoming) "Order line as
-- entity vs join" + the TASKS.md scope rationale.
--
-- Design notes :
--   - unit_price_at_order is IMMUTABLE post-insert : enforced by application
--     layer (no PUT endpoint accepts unit_price_at_order ; only quantity +
--     status). The DB doesn't enforce immutability natively — would need a
--     trigger. For a demo, application discipline is sufficient.
--   - quantity must be > 0 : zero-qty lines = use DELETE instead.
--   - order_id ON DELETE CASCADE : when an order is deleted, its lines go
--     too (no orphan lines). This is the safe direction.
--   - product_id ON DELETE RESTRICT : refuse deleting a product still
--     referenced by any order line (so the snapshot has a consistent
--     "the product existed at this id" semantic — auditability).
--   - status independent from parent order : a line can be SHIPPED while
--     other lines are still PENDING (partial fulfilment).
-- =============================================================================

CREATE TABLE IF NOT EXISTS order_line (
    id                      BIGSERIAL       PRIMARY KEY,
    order_id                BIGINT          NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    product_id              BIGINT          NOT NULL REFERENCES product(id) ON DELETE RESTRICT,
    quantity                INTEGER         NOT NULL CHECK (quantity > 0),
    unit_price_at_order     NUMERIC(12, 2)  NOT NULL CHECK (unit_price_at_order >= 0),
    status                  VARCHAR(20)     NOT NULL DEFAULT 'PENDING'
                                            CHECK (status IN ('PENDING', 'SHIPPED', 'REFUNDED')),
    created_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_order_line_order_id   ON order_line(order_id);
CREATE INDEX IF NOT EXISTS idx_order_line_product_id ON order_line(product_id);

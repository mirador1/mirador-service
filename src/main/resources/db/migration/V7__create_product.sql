-- =============================================================================
-- V7 — Create the product table
--
-- Part of the "augmenter la surface fonctionnelle" backlog (TASKS.md 2026-04-26).
-- Independent entity (no FK dependencies). Sibling : V8 (orders) + V9
-- (order_line). Numbering picks up from V6 (app_user) which is the highest
-- existing migration.
--
-- Design notes :
--   - BIGSERIAL : same convention as V1 customer table.
--   - unit_price NUMERIC(12, 2) : stores money with 2-decimal precision up
--     to 9 999 999 999.99. Avoids float rounding errors. Java side maps to
--     BigDecimal (NOT double / float).
--   - stock_quantity INTEGER NOT NULL CHECK >= 0 : DB-level guard against
--     negative stock (defense in depth alongside Bean Validation in
--     CreateProductRequest). A separate inventory log table will track
--     movements at the entity-level, but the running balance lives here.
--   - name UNIQUE : product names must be distinct. If we ever need
--     versioned products (price-history, sku changes), introduce a
--     `sku` text column + relax the UNIQUE on name then.
--   - created_at + updated_at TIMESTAMPTZ : audit columns. updated_at gets
--     bumped by application code on every PUT (no trigger ; explicit + simple).
-- =============================================================================

CREATE TABLE IF NOT EXISTS product (
    id              BIGSERIAL       PRIMARY KEY,
    name            VARCHAR(255)    NOT NULL UNIQUE,
    description     TEXT,
    unit_price      NUMERIC(12, 2)  NOT NULL CHECK (unit_price >= 0),
    stock_quantity  INTEGER         NOT NULL DEFAULT 0 CHECK (stock_quantity >= 0),
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_product_name ON product(name);

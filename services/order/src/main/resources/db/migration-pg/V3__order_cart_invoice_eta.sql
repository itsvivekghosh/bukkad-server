-- Order service — V3: cart, invoice, ETA tables (Batch B depth)
CREATE TABLE carts (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    customer_id BIGINT       NOT NULL,
    status      VARCHAR(20)  NOT NULL,
    created_at  TIMESTAMP(6) NOT NULL,
    updated_at  TIMESTAMP(6) NOT NULL
);
CREATE INDEX idx_carts_customer ON carts (customer_id);

CREATE TABLE cart_items (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    cart_id       BIGINT        NOT NULL REFERENCES carts (id) ON DELETE CASCADE,
    menu_item_id  BIGINT        NOT NULL,
    item_name     VARCHAR(200)  NOT NULL,
    unit_price    NUMERIC(10,2) NOT NULL,
    quantity      INTEGER       NOT NULL,
    created_at    TIMESTAMP(6)  NOT NULL
);
CREATE INDEX idx_cart_items_cart ON cart_items (cart_id);

CREATE TABLE order_invoices (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id      BIGINT        NOT NULL UNIQUE REFERENCES orders (id),
    invoice_number VARCHAR(50)  NOT NULL,
    gst_amount    NUMERIC(10,2) NOT NULL DEFAULT 0,
    total         NUMERIC(12,2) NOT NULL,
    created_at    TIMESTAMP(6)  NOT NULL
);

CREATE TABLE order_eta_snapshots (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id      BIGINT       NOT NULL REFERENCES orders (id),
    eta_minutes   INTEGER      NOT NULL,
    actual_minutes INTEGER,
    created_at    TIMESTAMP(6) NOT NULL
);
CREATE INDEX idx_eta_order ON order_eta_snapshots (order_id, created_at);

-- Retention archive (Batch B depth): moved from the hot orders table by
-- OrderArchiveService once a day via the PG-native archive query below.
CREATE TABLE orders_archive (
    id            BIGINT NOT NULL,
    customer_id   BIGINT         NOT NULL,
    restaurant_id BIGINT         NOT NULL,
    status        VARCHAR(20)    NOT NULL,
    total_amount  NUMERIC(12,2)  NOT NULL,
    currency      VARCHAR(3)     NOT NULL DEFAULT 'INR',
    created_at    TIMESTAMP(6)   NOT NULL,
    updated_at    TIMESTAMP(6)   NOT NULL,
    PRIMARY KEY (id, created_at)
) PARTITION BY RANGE (created_at);

-- Default partition so the archive is writable immediately; finer monthly
-- partitions are added by the ops retention job.
CREATE TABLE orders_archive_default PARTITION OF orders_archive DEFAULT;
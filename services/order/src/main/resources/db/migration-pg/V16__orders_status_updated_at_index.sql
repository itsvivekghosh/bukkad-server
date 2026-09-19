-- V16: Composite index for stuck-order sweep (status + updated_at)
-- This index supports the query used by ScheduledOrderProcessor and
-- any admin listing that filters orders by status and recency.

CREATE INDEX IF NOT EXISTS idx_orders_status_updated_at
    ON orders (status, updated_at);

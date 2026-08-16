-- Align orders.status with Order.OrderStatus (includes SCHEDULED for scheduled orders).
-- Some environments were created with a MySQL ENUM that omitted SCHEDULED; use VARCHAR to match V1 baseline.
ALTER TABLE orders
    MODIFY COLUMN status VARCHAR(20) NOT NULL DEFAULT 'PLACED';

-- W2-ORDER-SEARCH (feature #3): supports the asynchronous order saga.
--
-- No new columns/tables: saga state lives in the orders.status column
-- (new AWAITING_PAYMENT / PAYMENT_FAILED values) and the payment verdicts
-- travel over payment.events.v1 (KafkaPlatformConfig), compensations over the
-- transactional outbox. What the sweep DOES need is a bounded, indexed scan:
-- StuckOrderSweep polls AWAITING_PAYMENT orders by status + updatedAt.

CREATE INDEX IF NOT EXISTS idx_orders_status_updated_at
    ON public.orders (status, updated_at);

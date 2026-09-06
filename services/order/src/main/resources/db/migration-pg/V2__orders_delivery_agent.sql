-- Delivery lifecycle support: the merchant app assigns a rider per order and
-- the rider app transitions picked-up / delivered against that assignment.
ALTER TABLE public.orders
    ADD COLUMN IF NOT EXISTS delivery_agent_id bigint;

CREATE INDEX IF NOT EXISTS idx_orders_delivery_agent
    ON public.orders (delivery_agent_id);

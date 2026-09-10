-- W1-MONEY (audit feature #1): webhook-path lookup indexes.
-- The PSP webhook resolution matches by provider_ref / gateway_order_id on
-- every payment.captured / payment.refunded delivery; both columns previously
-- had no index (sequential scan per webhook under load).

CREATE INDEX IF NOT EXISTS idx_payments_provider_ref
    ON public.payments USING btree (provider_ref);

CREATE INDEX IF NOT EXISTS idx_payments_gateway_order_id
    ON public.payments USING btree (gateway_order_id);

CREATE INDEX IF NOT EXISTS idx_payments_idempotency_key
    ON public.payments USING btree (idempotency_key);

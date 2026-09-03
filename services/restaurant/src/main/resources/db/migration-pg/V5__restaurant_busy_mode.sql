-- ============================================================================
-- V5 — restaurant busy mode fields (Batch 4, migration-batch-4-restaurant)
-- ============================================================================
-- Adds restaurant busy-mode bookkeeping columns used by the new
-- com.bhukkad.restaurant.service.RestaurantBusyService.
-- ============================================================================

ALTER TABLE restaurants
    ADD COLUMN busy_mode           BOOLEAN     NOT NULL DEFAULT false,
    ADD COLUMN busy_until          TIMESTAMP(6),
    ADD COLUMN extra_prep_minutes  INTEGER     NOT NULL DEFAULT 0;
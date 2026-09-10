-- W2-ORDER-SEARCH (ADR-002): search-sync consumer + reconciliation sweep.
--
-- 1. The read tables already exist (V1 baseline: restaurant_search,
--    menu_item_search). The consumer upserts them by PRIMARY KEY id
--    (the projection document key IS the source id), so no new constraint
--    is needed for ON CONFLICT (id) DO UPDATE.
-- 2. menu_item_search.restaurant_id: additive, nullable backfill column keyed
--    from the menu_item_changed payload. The reconciliation sweep needs it to
--    scope per-restaurant repairs and delete propagation (items whose source
--    menu no longer contains them). The delete event does not need it, but the
--    sweep cannot repair what it cannot scope.
-- 3. shedlock: the periodic reconciliation sweep (@Scheduled) must run on a
--    single replica. Standard shedlock 5.x schema (same as delivery V9).

ALTER TABLE public.menu_item_search
    ADD COLUMN IF NOT EXISTS restaurant_id bigint;

CREATE INDEX IF NOT EXISTS idx_menu_item_search_restaurant_id
    ON public.menu_item_search (restaurant_id);

CREATE TABLE IF NOT EXISTS public.shedlock (
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    CONSTRAINT shedlock_pkey PRIMARY KEY (name)
);

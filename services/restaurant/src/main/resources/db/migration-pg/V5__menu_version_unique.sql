-- Menu snapshot versions must be unique per restaurant: the read-then-insert
-- race in MenuVersionService (audit batch C) minted duplicate version numbers
-- under concurrent publishes. DB-truth guard; the service handles the
-- violation with a bounded retry in fresh transactions.
CREATE UNIQUE INDEX IF NOT EXISTS uq_menu_versions_restaurant_version
    ON public.menu_versions (restaurant_id, version);

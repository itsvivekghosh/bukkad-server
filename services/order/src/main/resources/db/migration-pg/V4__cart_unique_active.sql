-- Audit batch A (cart race): concurrent POST /cart/items could double-create
-- ACTIVE carts and duplicate (cart, menu item) lines because CartService
-- select-then-insert with no mutual exclusion. These unique constraints make
-- the database the arbiter; CartService.addItem resolves the losing race with
-- ONE retry that adopts the winning cart row / merges quantity into the
-- winning line.
-- NOTE: on production data containing legacy duplicates these CREATE UNIQUE
-- INDEX statements fail the migration, which is the desired outcome —
-- de-duplication is a destructive change requiring explicit sign-off, so no
-- DELETE-based cleanup is performed here.

CREATE UNIQUE INDEX uq_carts_customer_active
    ON carts (customer_id)
    WHERE status = 'ACTIVE';

CREATE UNIQUE INDEX uq_cart_item_unique
    ON cart_items (cart_id, menu_item_id);

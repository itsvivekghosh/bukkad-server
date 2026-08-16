-- V21: Create inventory_alerts table
--
-- The InventoryAlert entity existed but the table was missing from the database.
-- This migration creates the table for low stock / out of stock / critical stock alerts.

CREATE TABLE IF NOT EXISTS inventory_alerts (
    id              BIGINT        AUTO_INCREMENT PRIMARY KEY,
    restaurant_id   BIGINT        NOT NULL,
    menu_item_id    BIGINT        NOT NULL,
    type            VARCHAR(20)   NOT NULL,
    current_stock   INT           NOT NULL,
    threshold       INT           NOT NULL,
    acknowledged    BIT(1)        NOT NULL DEFAULT 0,
    sent            BIT(1)        NOT NULL DEFAULT 0,
    created_at      DATETIME(6)   NOT NULL,
    INDEX idx_inventory_alert_restaurant   (restaurant_id),
    INDEX idx_inventory_alert_menu_item    (menu_item_id),
    INDEX idx_inventory_alert_type         (type),
    INDEX idx_inventory_alert_created_at   (created_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

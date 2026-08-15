-- Platform features: stock, tips, referrals, favorites, menu item ratings

ALTER TABLE menu_items
    ADD COLUMN stock_quantity INT NULL COMMENT 'NULL = unlimited stock';

ALTER TABLE orders
    ADD COLUMN tip_amount DOUBLE NOT NULL DEFAULT 0;

ALTER TABLE customers
    ADD COLUMN referral_code VARCHAR(20) NULL,
    ADD COLUMN referred_by_customer_id BIGINT NULL,
    ADD CONSTRAINT fk_customer_referred_by FOREIGN KEY (referred_by_customer_id) REFERENCES customers(id);

CREATE UNIQUE INDEX uk_customer_referral_code ON customers(referral_code);

CREATE TABLE favorite_restaurants (
    id BIGINT NOT NULL AUTO_INCREMENT,
    customer_id BIGINT NOT NULL,
    restaurant_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_fav_customer_restaurant (customer_id, restaurant_id),
    CONSTRAINT fk_fav_customer FOREIGN KEY (customer_id) REFERENCES customers(id),
    CONSTRAINT fk_fav_restaurant FOREIGN KEY (restaurant_id) REFERENCES restaurants(id),
    INDEX idx_fav_customer (customer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE menu_item_ratings (
    id BIGINT NOT NULL AUTO_INCREMENT,
    customer_id BIGINT NOT NULL,
    menu_item_id BIGINT NOT NULL,
    order_id BIGINT NOT NULL,
    rating INT NOT NULL,
    comment VARCHAR(500),
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_item_rating_order_item (order_id, menu_item_id),
    CONSTRAINT fk_mir_customer FOREIGN KEY (customer_id) REFERENCES customers(id),
    CONSTRAINT fk_mir_menu_item FOREIGN KEY (menu_item_id) REFERENCES menu_items(id),
    CONSTRAINT fk_mir_order FOREIGN KEY (order_id) REFERENCES orders(id),
    INDEX idx_mir_menu_item (menu_item_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

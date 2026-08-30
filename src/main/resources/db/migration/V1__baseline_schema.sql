-- Bhukkad consolidated database schema (squashed baseline: legacy V1-V26 series,
-- standalone V2 platform operations, and V27-V61, concatenated in their true
-- Flyway execution order; see README.md)

-- Bhukkad consolidated database schema (merged from V1-V26)


-- === V1__baseline_schema.sql ===
-- Users table (base)
CREATE TABLE IF NOT EXISTS users (
    id BIGINT NOT NULL AUTO_INCREMENT,
    email VARCHAR(100) NOT NULL,
    password VARCHAR(255) NOT NULL,
    full_name VARCHAR(100) NOT NULL,
    phone_number VARCHAR(15),
    role VARCHAR(20) NOT NULL,
    active BIT(1) NOT NULL DEFAULT 1,
    email_verified BIT(1) NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6),
    profile_image_url VARCHAR(500),
    PRIMARY KEY (id),
    UNIQUE KEY idx_user_email (email),
    INDEX idx_user_phone (phone_number),
    INDEX idx_user_role (role),
    INDEX idx_user_active (active),
    INDEX idx_user_role_active (role, active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Customers table
CREATE TABLE IF NOT EXISTS customers (
    id BIGINT NOT NULL,
    loyalty_points INT NOT NULL DEFAULT 0,
    wallet_balance DOUBLE NOT NULL DEFAULT 0.0,
    PRIMARY KEY (id),
    CONSTRAINT fk_customer_user FOREIGN KEY (id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Restaurant Owners table
CREATE TABLE IF NOT EXISTS restaurant_owners (
    id BIGINT NOT NULL,
    business_license VARCHAR(100),
    verified BIT(1) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT fk_owner_user FOREIGN KEY (id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Delivery Agents table
CREATE TABLE IF NOT EXISTS delivery_agents (
    id BIGINT NOT NULL,
    vehicle_type VARCHAR(50),
    vehicle_number VARCHAR(50),
    license_number VARCHAR(50),
    available BIT(1) NOT NULL DEFAULT 1,
    verified BIT(1) NOT NULL DEFAULT 0,
    current_latitude DOUBLE,
    current_longitude DOUBLE,
    average_rating DOUBLE DEFAULT 0.0,
    total_deliveries INT DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT fk_agent_user FOREIGN KEY (id) REFERENCES users(id),
    INDEX idx_agent_available (available),
    INDEX idx_agent_verified (verified),
    INDEX idx_agent_available_verified (available, verified)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Addresses table
CREATE TABLE IF NOT EXISTS addresses (
    id BIGINT NOT NULL AUTO_INCREMENT,
    customer_id BIGINT,
    address_line1 VARCHAR(500) NOT NULL,
    address_line2 VARCHAR(500),
    city VARCHAR(100) NOT NULL,
    state VARCHAR(100) NOT NULL,
    pincode VARCHAR(10) NOT NULL,
    landmark VARCHAR(200),
    type VARCHAR(20),
    label VARCHAR(50),
    latitude DOUBLE NOT NULL,
    longitude DOUBLE NOT NULL,
    is_default BIT(1) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT fk_address_customer FOREIGN KEY (customer_id) REFERENCES customers(id),
    INDEX idx_address_customer (customer_id),
    INDEX idx_address_city (city),
    INDEX idx_address_pincode (pincode)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Cuisines table
CREATE TABLE IF NOT EXISTS cuisines (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL,
    image_url VARCHAR(500),
    active BIT(1) NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY idx_cuisine_name (name),
    INDEX idx_cuisine_active (active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Restaurants table
CREATE TABLE IF NOT EXISTS restaurants (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(200) NOT NULL,
    description VARCHAR(2000),
    owner_id BIGINT NOT NULL,
    address_id BIGINT,
    image_url VARCHAR(500),
    opening_time TIME NOT NULL,
    closing_time TIME NOT NULL,
    is_active BIT(1) NOT NULL DEFAULT 1,
    is_open BIT(1) NOT NULL DEFAULT 1,
    average_rating DOUBLE DEFAULT 0.0,
    total_reviews INT DEFAULT 0,
    average_delivery_time INT,
    minimum_order_amount DOUBLE,
    delivery_fee DOUBLE,
    free_delivery_available BIT(1) NOT NULL DEFAULT 0,
    free_delivery_above DOUBLE,
    is_pure_veg BIT(1) NOT NULL DEFAULT 0,
    license_number VARCHAR(50),
    fssai_number VARCHAR(50),
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_restaurant_owner FOREIGN KEY (owner_id) REFERENCES restaurant_owners(id),
    CONSTRAINT fk_restaurant_address FOREIGN KEY (address_id) REFERENCES addresses(id),
    INDEX idx_restaurant_name (name),
    INDEX idx_restaurant_owner (owner_id),
    INDEX idx_restaurant_active (is_active),
    INDEX idx_restaurant_open (is_open),
    INDEX idx_restaurant_rating (average_rating),
    INDEX idx_restaurant_active_open (is_active, is_open)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Restaurant Cuisines (Many-to-Many)
CREATE TABLE IF NOT EXISTS restaurant_cuisines (
    restaurant_id BIGINT NOT NULL,
    cuisine_id BIGINT NOT NULL,
    PRIMARY KEY (restaurant_id, cuisine_id),
    CONSTRAINT fk_rc_restaurant FOREIGN KEY (restaurant_id) REFERENCES restaurants(id),
    CONSTRAINT fk_rc_cuisine FOREIGN KEY (cuisine_id) REFERENCES cuisines(id),
    INDEX idx_rc_restaurant (restaurant_id),
    INDEX idx_rc_cuisine (cuisine_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Menu Categories table
CREATE TABLE IF NOT EXISTS menu_categories (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    restaurant_id BIGINT NOT NULL,
    display_order INT DEFAULT 0,
    active BIT(1) NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    CONSTRAINT fk_category_restaurant FOREIGN KEY (restaurant_id) REFERENCES restaurants(id),
    INDEX idx_category_restaurant (restaurant_id),
    INDEX idx_category_active (active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Menu Items table
CREATE TABLE IF NOT EXISTS menu_items (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(200) NOT NULL,
    description VARCHAR(2000),
    category_id BIGINT NOT NULL,
    price DOUBLE NOT NULL,
    original_price DOUBLE,
    discount_percentage DOUBLE,
    available BIT(1) NOT NULL DEFAULT 1,
    food_type VARCHAR(20) NOT NULL,
    is_veg BIT(1) NOT NULL,
    is_spicy BIT(1) DEFAULT 0,
    spice_level VARCHAR(20),
    image_url VARCHAR(500),
    preparation_time INT,
    bestseller BIT(1) DEFAULT 0,
    recommended BIT(1) DEFAULT 0,
    calories INT,
    serving_size VARCHAR(50),
    average_rating DOUBLE DEFAULT 0.0,
    total_ratings INT DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_item_category FOREIGN KEY (category_id) REFERENCES menu_categories(id),
    INDEX idx_menu_item_category (category_id),
    INDEX idx_menu_item_name (name),
    INDEX idx_menu_item_available (available),
    INDEX idx_menu_item_food_type (food_type),
    INDEX idx_menu_item_is_veg (is_veg),
    INDEX idx_menu_item_bestseller (bestseller)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Customization Options
CREATE TABLE IF NOT EXISTS customization_options (
    id BIGINT NOT NULL AUTO_INCREMENT,
    menu_item_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    required BIT(1) NOT NULL DEFAULT 0,
    multiple_selection BIT(1) NOT NULL DEFAULT 0,
    min_selection INT DEFAULT 0,
    max_selection INT,
    PRIMARY KEY (id),
    CONSTRAINT fk_option_item FOREIGN KEY (menu_item_id) REFERENCES menu_items(id),
    INDEX idx_option_item (menu_item_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Customization Choices
CREATE TABLE IF NOT EXISTS customization_choices (
    id BIGINT NOT NULL AUTO_INCREMENT,
    customization_option_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    additional_price DOUBLE DEFAULT 0.0,
    available BIT(1) NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    CONSTRAINT fk_choice_option FOREIGN KEY (customization_option_id) REFERENCES customization_options(id),
    INDEX idx_choice_option (customization_option_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Coupons table
CREATE TABLE IF NOT EXISTS coupons (
    id BIGINT NOT NULL AUTO_INCREMENT,
    code VARCHAR(50) NOT NULL,
    description VARCHAR(500) NOT NULL,
    discount_type VARCHAR(20) NOT NULL,
    discount_value DOUBLE NOT NULL,
    minimum_order_amount DOUBLE,
    maximum_discount_amount DOUBLE,
    valid_from DATETIME(6) NOT NULL,
    valid_until DATETIME(6) NOT NULL,
    usage_limit INT,
    used_count INT DEFAULT 0,
    per_user_limit INT,
    active BIT(1) NOT NULL DEFAULT 1,
    restaurant_id BIGINT,
    PRIMARY KEY (id),
    UNIQUE KEY idx_coupon_code (code),
    CONSTRAINT fk_coupon_restaurant FOREIGN KEY (restaurant_id) REFERENCES restaurants(id),
    INDEX idx_coupon_active (active),
    INDEX idx_coupon_valid (active, valid_from, valid_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Carts table
CREATE TABLE IF NOT EXISTS carts (
    id BIGINT NOT NULL AUTO_INCREMENT,
    customer_id BIGINT NOT NULL,
    restaurant_id BIGINT,
    updated_at DATETIME(6),
    PRIMARY KEY (id),
    UNIQUE KEY idx_cart_customer (customer_id),
    CONSTRAINT fk_cart_customer FOREIGN KEY (customer_id) REFERENCES customers(id),
    CONSTRAINT fk_cart_restaurant FOREIGN KEY (restaurant_id) REFERENCES restaurants(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Cart Items table
CREATE TABLE IF NOT EXISTS cart_items (
    id BIGINT NOT NULL AUTO_INCREMENT,
    cart_id BIGINT NOT NULL,
    menu_item_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    special_instructions VARCHAR(500),
    PRIMARY KEY (id),
    CONSTRAINT fk_cart_item_cart FOREIGN KEY (cart_id) REFERENCES carts(id),
    CONSTRAINT fk_cart_item_menu FOREIGN KEY (menu_item_id) REFERENCES menu_items(id),
    INDEX idx_cart_item_cart (cart_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Cart Item Customizations
CREATE TABLE IF NOT EXISTS cart_item_customizations (
    id BIGINT NOT NULL AUTO_INCREMENT,
    cart_item_id BIGINT NOT NULL,
    customization_choice_id BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_cart_custom_item FOREIGN KEY (cart_item_id) REFERENCES cart_items(id),
    CONSTRAINT fk_cart_custom_choice FOREIGN KEY (customization_choice_id) REFERENCES customization_choices(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Orders table
CREATE TABLE IF NOT EXISTS orders (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_number VARCHAR(50) NOT NULL,
    customer_id BIGINT NOT NULL,
    restaurant_id BIGINT NOT NULL,
    delivery_address_id BIGINT NOT NULL,
    delivery_agent_id BIGINT,
    status VARCHAR(20) NOT NULL DEFAULT 'PLACED',
    subtotal DOUBLE NOT NULL,
    delivery_fee DOUBLE,
    tax_amount DOUBLE,
    discount_amount DOUBLE,
    total_amount DOUBLE NOT NULL,
    coupon_id BIGINT,
    special_instructions VARCHAR(1000),
    contactless_delivery BIT(1) DEFAULT 0,
    estimated_delivery_time INT,
    estimated_delivery_at DATETIME(6),
    delivered_at DATETIME(6),
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6),
    PRIMARY KEY (id),
    UNIQUE KEY idx_order_number (order_number),
    CONSTRAINT fk_order_customer FOREIGN KEY (customer_id) REFERENCES customers(id),
    CONSTRAINT fk_order_restaurant FOREIGN KEY (restaurant_id) REFERENCES restaurants(id),
    CONSTRAINT fk_order_address FOREIGN KEY (delivery_address_id) REFERENCES addresses(id),
    CONSTRAINT fk_order_agent FOREIGN KEY (delivery_agent_id) REFERENCES delivery_agents(id),
    CONSTRAINT fk_order_coupon FOREIGN KEY (coupon_id) REFERENCES coupons(id),
    INDEX idx_order_customer (customer_id),
    INDEX idx_order_restaurant (restaurant_id),
    INDEX idx_order_status (status),
    INDEX idx_order_created (created_at),
    INDEX idx_order_customer_status (customer_id, status),
    INDEX idx_order_restaurant_status (restaurant_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Order Items table
CREATE TABLE IF NOT EXISTS order_items (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    menu_item_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    price DOUBLE NOT NULL,
    special_instructions VARCHAR(500),
    PRIMARY KEY (id),
    CONSTRAINT fk_order_item_order FOREIGN KEY (order_id) REFERENCES orders(id),
    CONSTRAINT fk_order_item_menu FOREIGN KEY (menu_item_id) REFERENCES menu_items(id),
    INDEX idx_order_item_order (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Order Item Customizations
CREATE TABLE IF NOT EXISTS order_item_customizations (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_item_id BIGINT NOT NULL,
    customization_choice_id BIGINT NOT NULL,
    additional_price DOUBLE,
    PRIMARY KEY (id),
    CONSTRAINT fk_order_custom_item FOREIGN KEY (order_item_id) REFERENCES order_items(id),
    CONSTRAINT fk_order_custom_choice FOREIGN KEY (customization_choice_id) REFERENCES customization_choices(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Payments table
CREATE TABLE IF NOT EXISTS payments (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    payment_method VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    amount DOUBLE NOT NULL,
    transaction_id VARCHAR(100),
    payment_gateway_response VARCHAR(2000),
    created_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6),
    PRIMARY KEY (id),
    UNIQUE KEY idx_payment_order (order_id),
    CONSTRAINT fk_payment_order FOREIGN KEY (order_id) REFERENCES orders(id),
    INDEX idx_payment_status (status),
    INDEX idx_payment_transaction (transaction_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Reviews table
CREATE TABLE IF NOT EXISTS reviews (
    id BIGINT NOT NULL AUTO_INCREMENT,
    customer_id BIGINT NOT NULL,
    restaurant_id BIGINT NOT NULL,
    order_id BIGINT NOT NULL,
    rating INT NOT NULL,
    comment VARCHAR(2000),
    food_rating INT,
    delivery_rating INT,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY idx_review_order (order_id),
    CONSTRAINT fk_review_customer FOREIGN KEY (customer_id) REFERENCES customers(id),
    CONSTRAINT fk_review_restaurant FOREIGN KEY (restaurant_id) REFERENCES restaurants(id),
    CONSTRAINT fk_review_order FOREIGN KEY (order_id) REFERENCES orders(id),
    INDEX idx_review_customer (customer_id),
    INDEX idx_review_restaurant (restaurant_id),
    INDEX idx_review_rating (rating),
    INDEX idx_review_restaurant_rating (restaurant_id, rating)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- === V2__reliability_features.sql ===
-- Production reliability: optimistic locking, outbox, idempotency, payment gateway columns

ALTER TABLE orders
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE payments
    ADD COLUMN gateway_order_id VARCHAR(100),
    ADD COLUMN gateway_payment_id VARCHAR(100),
    ADD COLUMN idempotency_key VARCHAR(128),
    ADD UNIQUE INDEX idx_payment_idempotency_key (idempotency_key);

CREATE TABLE outbox_events (
    id BIGINT NOT NULL AUTO_INCREMENT,
    event_type VARCHAR(80) NOT NULL,
    aggregate_type VARCHAR(50) NOT NULL,
    aggregate_id BIGINT NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    retry_count INT NOT NULL DEFAULT 0,
    last_error VARCHAR(1000),
    created_at DATETIME(6) NOT NULL,
    published_at DATETIME(6),
    PRIMARY KEY (id),
    INDEX idx_outbox_status_created (status, created_at),
    INDEX idx_outbox_aggregate (aggregate_type, aggregate_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE idempotency_records (
    id BIGINT NOT NULL AUTO_INCREMENT,
    idempotency_key VARCHAR(128) NOT NULL,
    scope VARCHAR(50) NOT NULL,
    owner_id BIGINT,
    status VARCHAR(20) NOT NULL,
    response_payload TEXT,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_idempotency_scope_key (scope, idempotency_key),
    INDEX idx_idempotency_expires (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- === V3__search_and_geo_indexes.sql ===
-- Geo lookup and full-text search indexes

CREATE INDEX idx_address_lat_lon ON addresses (latitude, longitude);

ALTER TABLE restaurants
    ADD FULLTEXT INDEX ft_restaurant_name (name);

ALTER TABLE menu_items
    ADD FULLTEXT INDEX ft_menu_item_search (name, description);

-- === V4__platform_enhancements.sql ===
-- Order payment breakdown & loyalty tracking
ALTER TABLE orders
    ADD COLUMN loyalty_points_redeemed INT NOT NULL DEFAULT 0,
    ADD COLUMN wallet_amount_used DOUBLE NOT NULL DEFAULT 0;

-- Payment: split pay + wallet top-up
ALTER TABLE payments
    MODIFY COLUMN order_id BIGINT NULL,
    ADD COLUMN customer_id BIGINT NULL,
    ADD COLUMN purpose VARCHAR(30) NOT NULL DEFAULT 'ORDER',
    ADD COLUMN wallet_amount DOUBLE NOT NULL DEFAULT 0,
    ADD COLUMN gateway_amount DOUBLE NOT NULL DEFAULT 0,
    ADD CONSTRAINT fk_payment_customer FOREIGN KEY (customer_id) REFERENCES customers(id);

CREATE INDEX idx_payment_customer ON payments(customer_id);
CREATE INDEX idx_payment_purpose ON payments(purpose);

-- Wallet ledger
CREATE TABLE wallet_transactions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    customer_id BIGINT NOT NULL,
    payment_id BIGINT NULL,
    type VARCHAR(30) NOT NULL,
    amount DOUBLE NOT NULL,
    balance_after DOUBLE NOT NULL,
    description VARCHAR(500),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_wallet_tx_customer FOREIGN KEY (customer_id) REFERENCES customers(id),
    CONSTRAINT fk_wallet_tx_payment FOREIGN KEY (payment_id) REFERENCES payments(id)
);

CREATE INDEX idx_wallet_tx_customer ON wallet_transactions(customer_id, created_at);

-- Push notification device tokens
CREATE TABLE device_tokens (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    token VARCHAR(512) NOT NULL,
    platform VARCHAR(20) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NULL,
    CONSTRAINT fk_device_token_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT uk_device_token UNIQUE (token)
);

CREATE INDEX idx_device_token_user ON device_tokens(user_id, active);

-- Rider earnings ledger
CREATE TABLE rider_earnings (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    agent_id BIGINT NOT NULL,
    order_id BIGINT NOT NULL,
    amount DOUBLE NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    paid_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_rider_earning_agent FOREIGN KEY (agent_id) REFERENCES delivery_agents(id),
    CONSTRAINT fk_rider_earning_order FOREIGN KEY (order_id) REFERENCES orders(id),
    CONSTRAINT uk_rider_earning_order UNIQUE (order_id)
);

CREATE INDEX idx_rider_earning_agent ON rider_earnings(agent_id, status);

-- === V5__seed_reference_data.sql ===
-- Reference data for local/dev environments

INSERT INTO cuisines (name, image_url, active) VALUES
('Indian', 'https://img.bhukkad.com/cuisines/indian.jpg', 1),
('Chinese', 'https://img.bhukkad.com/cuisines/chinese.jpg', 1),
('Italian', 'https://img.bhukkad.com/cuisines/italian.jpg', 1),
('Mexican', 'https://img.bhukkad.com/cuisines/mexican.jpg', 1),
('Thai', 'https://img.bhukkad.com/cuisines/thai.jpg', 1),
('Japanese', 'https://img.bhukkad.com/cuisines/japanese.jpg', 1),
('Continental', 'https://img.bhukkad.com/cuisines/continental.jpg', 1),
('Fast Food', 'https://img.bhukkad.com/cuisines/fastfood.jpg', 1),
('South Indian', 'https://img.bhukkad.com/cuisines/south-indian.jpg', 1),
('North Indian', 'https://img.bhukkad.com/cuisines/north-indian.jpg', 1),
('Mughlai', 'https://img.bhukkad.com/cuisines/mughlai.jpg', 1),
('Street Food', 'https://img.bhukkad.com/cuisines/street-food.jpg', 1),
('Biryani', 'https://img.bhukkad.com/cuisines/biryani.jpg', 1),
('Desserts', 'https://img.bhukkad.com/cuisines/desserts.jpg', 1),
('Beverages', 'https://img.bhukkad.com/cuisines/beverages.jpg', 1),
('Pizza', 'https://img.bhukkad.com/cuisines/pizza.jpg', 1),
('Burger', 'https://img.bhukkad.com/cuisines/burger.jpg', 1),
('Rolls', 'https://img.bhukkad.com/cuisines/rolls.jpg', 1),
('Sandwich', 'https://img.bhukkad.com/cuisines/sandwich.jpg', 1),
('Cake', 'https://img.bhukkad.com/cuisines/cake.jpg', 1)
ON DUPLICATE KEY UPDATE name = VALUES(name);

INSERT INTO coupons (code, description, discount_type, discount_value, minimum_order_amount, maximum_discount_amount, valid_from, valid_until, usage_limit, used_count, per_user_limit, active) VALUES
('WELCOME50', 'Get 50% off on your first order', 'PERCENTAGE', 50.00, 200.00, 150.00, NOW(), DATE_ADD(NOW(), INTERVAL 1 YEAR), 10000, 0, 1, 1),
('BHUKKAD100', 'Flat Rs.100 off on orders above Rs.500', 'FIXED_AMOUNT', 100.00, 500.00, 100.00, NOW(), DATE_ADD(NOW(), INTERVAL 6 MONTH), 50000, 0, 3, 1),
('FREEDEL', 'Free delivery on any order above Rs.99', 'FIXED_AMOUNT', 40.00, 99.00, 40.00, NOW(), DATE_ADD(NOW(), INTERVAL 3 MONTH), 100000, 0, 5, 1),
('SUPER20', 'Get 20% off on orders above Rs.300', 'PERCENTAGE', 20.00, 300.00, 80.00, NOW(), DATE_ADD(NOW(), INTERVAL 6 MONTH), 50000, 0, 10, 1),
('FEAST30', 'Get 30% off on orders above Rs.600', 'PERCENTAGE', 30.00, 600.00, 200.00, NOW(), DATE_ADD(NOW(), INTERVAL 3 MONTH), 20000, 0, 2, 1),
('FLAT150', 'Flat Rs.150 off on orders above Rs.750', 'FIXED_AMOUNT', 150.00, 750.00, 150.00, NOW(), DATE_ADD(NOW(), INTERVAL 6 MONTH), 30000, 0, 5, 1)
ON DUPLICATE KEY UPDATE code = VALUES(code);

-- === V6__restaurant_element_collections.sql ===
-- Element-collection tables for Restaurant entity (@ElementCollection)

CREATE TABLE IF NOT EXISTS restaurant_features (
    restaurant_id BIGINT NOT NULL,
    feature VARCHAR(100) NOT NULL,
    PRIMARY KEY (restaurant_id, feature),
    CONSTRAINT fk_restaurant_features_restaurant
        FOREIGN KEY (restaurant_id) REFERENCES restaurants(id) ON DELETE CASCADE,
    INDEX idx_restaurant_features_restaurant (restaurant_id)
);

CREATE TABLE IF NOT EXISTS restaurant_gallery (
    restaurant_id BIGINT NOT NULL,
    image_url VARCHAR(500) NOT NULL,
    PRIMARY KEY (restaurant_id, image_url),
    CONSTRAINT fk_restaurant_gallery_restaurant
        FOREIGN KEY (restaurant_id) REFERENCES restaurants(id) ON DELETE CASCADE,
    INDEX idx_restaurant_gallery_restaurant (restaurant_id)
);

CREATE TABLE IF NOT EXISTS restaurant_food_types (
    restaurant_id BIGINT NOT NULL,
    food_type VARCHAR(50) NOT NULL,
    PRIMARY KEY (restaurant_id, food_type),
    CONSTRAINT fk_restaurant_food_types_restaurant
        FOREIGN KEY (restaurant_id) REFERENCES restaurants(id) ON DELETE CASCADE,
    INDEX idx_restaurant_food_types_restaurant (restaurant_id)
);

-- === V7__menu_item_element_collections.sql ===
-- Element-collection tables for MenuItem entity (@ElementCollection)

CREATE TABLE IF NOT EXISTS menu_item_tags (
    menu_item_id BIGINT NOT NULL,
    tag VARCHAR(100) NOT NULL,
    PRIMARY KEY (menu_item_id, tag),
    CONSTRAINT fk_menu_item_tags_item
        FOREIGN KEY (menu_item_id) REFERENCES menu_items(id) ON DELETE CASCADE,
    INDEX idx_menu_item_tags_item (menu_item_id)
);

CREATE TABLE IF NOT EXISTS menu_item_allergens (
    menu_item_id BIGINT NOT NULL,
    allergen VARCHAR(100) NOT NULL,
    PRIMARY KEY (menu_item_id, allergen),
    CONSTRAINT fk_menu_item_allergens_item
        FOREIGN KEY (menu_item_id) REFERENCES menu_items(id) ON DELETE CASCADE,
    INDEX idx_menu_item_allergens_item (menu_item_id)
);

CREATE TABLE IF NOT EXISTS menu_item_ingredients (
    menu_item_id BIGINT NOT NULL,
    ingredient VARCHAR(200) NOT NULL,
    PRIMARY KEY (menu_item_id, ingredient),
    CONSTRAINT fk_menu_item_ingredients_item
        FOREIGN KEY (menu_item_id) REFERENCES menu_items(id) ON DELETE CASCADE,
    INDEX idx_menu_item_ingredients_item (menu_item_id)
);

CREATE TABLE IF NOT EXISTS menu_item_images (
    menu_item_id BIGINT NOT NULL,
    image_url VARCHAR(500) NOT NULL,
    PRIMARY KEY (menu_item_id, image_url),
    CONSTRAINT fk_menu_item_images_item
        FOREIGN KEY (menu_item_id) REFERENCES menu_items(id) ON DELETE CASCADE,
    INDEX idx_menu_item_images_item (menu_item_id)
);

-- === V8__review_images.sql ===
-- Element-collection table for Review.images (@ElementCollection)

CREATE TABLE IF NOT EXISTS review_images (
    review_id BIGINT NOT NULL,
    images VARCHAR(500) NOT NULL,
    PRIMARY KEY (review_id, images),
    CONSTRAINT fk_review_images_review
        FOREIGN KEY (review_id) REFERENCES reviews(id) ON DELETE CASCADE,
    INDEX idx_review_images_review (review_id)
);

-- === V9__platform_features.sql ===
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

-- === V10__advanced_platform.sql ===
-- Scheduled orders, live ETA, restaurant settlement ledger

ALTER TABLE orders
    ADD COLUMN scheduled_at DATETIME(6) NULL,
    ADD COLUMN live_eta_minutes INT NULL,
    ADD COLUMN live_eta_at DATETIME(6) NULL;

CREATE INDEX idx_order_scheduled_at ON orders(scheduled_at);

CREATE TABLE restaurant_settlements (
    id BIGINT NOT NULL AUTO_INCREMENT,
    restaurant_id BIGINT NOT NULL,
    order_id BIGINT NOT NULL,
    order_amount DOUBLE NOT NULL,
    commission_amount DOUBLE NOT NULL,
    net_amount DOUBLE NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    settled_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_settlement_order (order_id),
    CONSTRAINT fk_settlement_restaurant FOREIGN KEY (restaurant_id) REFERENCES restaurants(id),
    CONSTRAINT fk_settlement_order FOREIGN KEY (order_id) REFERENCES orders(id),
    INDEX idx_settlement_restaurant_status (restaurant_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- === V11__platform_enhancements.sql ===
-- Coupon per-user tracking, restaurant commission override, notification preferences

CREATE TABLE coupon_usages (
    id BIGINT NOT NULL AUTO_INCREMENT,
    coupon_id BIGINT NOT NULL,
    customer_id BIGINT NOT NULL,
    order_id BIGINT NULL,
    used_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_coupon_usage_coupon FOREIGN KEY (coupon_id) REFERENCES coupons(id),
    CONSTRAINT fk_coupon_usage_customer FOREIGN KEY (customer_id) REFERENCES customers(id),
    CONSTRAINT fk_coupon_usage_order FOREIGN KEY (order_id) REFERENCES orders(id),
    INDEX idx_coupon_usage_coupon_customer (coupon_id, customer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE restaurants
    ADD COLUMN commission_percent DOUBLE NULL;

CREATE TABLE customer_notification_preferences (
    customer_id BIGINT NOT NULL,
    email_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    sms_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    push_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    order_updates_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    promotions_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at DATETIME(6) NULL,
    PRIMARY KEY (customer_id),
    CONSTRAINT fk_notif_pref_customer FOREIGN KEY (customer_id) REFERENCES customers(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- === V12__notification_whatsapp.sql ===
-- WhatsApp notification preference channel

ALTER TABLE customer_notification_preferences
    ADD COLUMN whatsapp_enabled BOOLEAN NOT NULL DEFAULT TRUE;

-- === V13__growth_operations.sql ===
-- V13: Growth & operations — zones, support, invoices, membership, home feed, timeline, rider map

-- Delivery zones for serviceability and dynamic pricing
CREATE TABLE delivery_zones (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    name            VARCHAR(120)  NOT NULL,
    city            VARCHAR(100)  NOT NULL,
    center_latitude DOUBLE        NOT NULL,
    center_longitude DOUBLE       NOT NULL,
    radius_km       DOUBLE        NOT NULL DEFAULT 5.0,
    base_delivery_fee DOUBLE      NOT NULL DEFAULT 40.0,
    per_km_fee      DOUBLE        NOT NULL DEFAULT 5.0,
    surge_multiplier DOUBLE       NOT NULL DEFAULT 1.0,
    is_active       BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_delivery_zones_city (city),
    INDEX idx_delivery_zones_active (is_active)
);

-- Customer support tickets
CREATE TABLE support_tickets (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    ticket_number   VARCHAR(30)   NOT NULL UNIQUE,
    customer_id     BIGINT        NOT NULL,
    order_id        BIGINT        NULL,
    category        VARCHAR(50)   NOT NULL,
    subject         VARCHAR(255)  NOT NULL,
    description     TEXT,
    status          VARCHAR(20)   NOT NULL DEFAULT 'OPEN',
    priority        VARCHAR(20)   NOT NULL DEFAULT 'MEDIUM',
    resolution_notes TEXT,
    created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_support_customer FOREIGN KEY (customer_id) REFERENCES customers(id),
    CONSTRAINT fk_support_order FOREIGN KEY (order_id) REFERENCES orders(id),
    INDEX idx_support_customer (customer_id),
    INDEX idx_support_status (status)
);

-- GST order invoices
CREATE TABLE order_invoices (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id        BIGINT        NOT NULL UNIQUE,
    invoice_number  VARCHAR(40)   NOT NULL UNIQUE,
    subtotal        DOUBLE        NOT NULL,
    delivery_fee    DOUBLE        NOT NULL DEFAULT 0,
    tax_amount      DOUBLE        NOT NULL DEFAULT 0,
    cgst_amount     DOUBLE        NOT NULL DEFAULT 0,
    sgst_amount     DOUBLE        NOT NULL DEFAULT 0,
    discount_amount DOUBLE        NOT NULL DEFAULT 0,
    total_amount    DOUBLE        NOT NULL,
    restaurant_gstin VARCHAR(20),
    issued_at       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_invoice_order FOREIGN KEY (order_id) REFERENCES orders(id)
);

-- Home feed promo banners
CREATE TABLE promo_banners (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    title           VARCHAR(150)  NOT NULL,
    subtitle        VARCHAR(255),
    image_url       VARCHAR(500),
    action_type     VARCHAR(30)   NOT NULL DEFAULT 'NONE',
    action_target   VARCHAR(255),
    display_order   INT           NOT NULL DEFAULT 0,
    is_active       BOOLEAN       NOT NULL DEFAULT TRUE,
    starts_at       TIMESTAMP     NULL,
    ends_at         TIMESTAMP     NULL,
    created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_promo_active (is_active, display_order)
);

-- Membership plans (Bhukkad One style)
CREATE TABLE membership_plans (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    name            VARCHAR(100)  NOT NULL,
    description     TEXT,
    price_per_month DOUBLE        NOT NULL,
    free_delivery   BOOLEAN       NOT NULL DEFAULT TRUE,
    discount_percent DOUBLE       NOT NULL DEFAULT 0,
    is_active       BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE customer_memberships (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    customer_id     BIGINT        NOT NULL,
    plan_id         BIGINT        NOT NULL,
    status          VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',
    starts_at       TIMESTAMP     NOT NULL,
    ends_at         TIMESTAMP     NOT NULL,
    created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_membership_customer FOREIGN KEY (customer_id) REFERENCES customers(id),
    CONSTRAINT fk_membership_plan FOREIGN KEY (plan_id) REFERENCES membership_plans(id),
    INDEX idx_membership_customer (customer_id, status)
);

-- Order timeline for support and tracking
CREATE TABLE order_timeline_events (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id        BIGINT        NOT NULL,
    event_type      VARCHAR(50)   NOT NULL,
    status          VARCHAR(30),
    message         VARCHAR(500),
    actor_id        BIGINT,
    actor_role      VARCHAR(30),
    created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_timeline_order FOREIGN KEY (order_id) REFERENCES orders(id),
    INDEX idx_timeline_order (order_id, created_at)
);

-- Rider GPS snapshots for live map
CREATE TABLE rider_location_updates (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id        BIGINT        NOT NULL,
    agent_id        BIGINT        NOT NULL,
    latitude        DOUBLE        NOT NULL,
    longitude       DOUBLE        NOT NULL,
    recorded_at     TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_rider_loc_order FOREIGN KEY (order_id) REFERENCES orders(id),
    CONSTRAINT fk_rider_loc_agent FOREIGN KEY (agent_id) REFERENCES delivery_agents(id),
    INDEX idx_rider_loc_order (order_id, recorded_at DESC)
);

-- Promotion campaigns beyond coupons
CREATE TABLE promotion_campaigns (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    name            VARCHAR(120)  NOT NULL,
    campaign_type   VARCHAR(30)   NOT NULL,
    description     TEXT,
    discount_percent DOUBLE       NULL,
    min_order_amount DOUBLE       NULL,
    is_active       BOOLEAN       NOT NULL DEFAULT TRUE,
    starts_at       TIMESTAMP     NULL,
    ends_at         TIMESTAMP     NULL,
    created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Fraud / abuse event log
CREATE TABLE fraud_events (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    customer_id     BIGINT        NULL,
    event_type      VARCHAR(50)   NOT NULL,
    device_fingerprint VARCHAR(128),
    ip_address      VARCHAR(45),
    details         TEXT,
    created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_fraud_customer (customer_id),
    INDEX idx_fraud_type (event_type)
);

-- Restaurant busy mode
ALTER TABLE restaurants
    ADD COLUMN busy_mode BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN busy_until TIMESTAMP NULL,
    ADD COLUMN extra_prep_minutes INT NOT NULL DEFAULT 0;

-- Order cancellation metadata
ALTER TABLE orders
    ADD COLUMN cancellation_reason VARCHAR(255),
    ADD COLUMN cancelled_by VARCHAR(30);

-- Loyalty tier on customer
ALTER TABLE customers
    ADD COLUMN loyalty_tier VARCHAR(20) NOT NULL DEFAULT 'BRONZE';

-- Review moderation
ALTER TABLE reviews
    ADD COLUMN moderation_status VARCHAR(20) NOT NULL DEFAULT 'APPROVED',
    ADD COLUMN owner_response TEXT;

-- Seed default membership plan and sample zone/banner
INSERT INTO membership_plans (name, description, price_per_month, free_delivery, discount_percent, is_active)
VALUES ('Bhukkad One', 'Free delivery and 5% off on all orders', 99.0, TRUE, 5.0, TRUE);

INSERT INTO delivery_zones (name, city, center_latitude, center_longitude, radius_km, base_delivery_fee, per_km_fee, surge_multiplier)
VALUES ('Bangalore Central', 'Bangalore', 12.9716, 77.5946, 15.0, 30.0, 4.0, 1.0);

INSERT INTO promo_banners (title, subtitle, image_url, action_type, action_target, display_order, is_active)
VALUES ('Welcome to Bhukkad', 'Order now and get fast delivery', NULL, 'NONE', NULL, 1, TRUE);

-- === V14__delivery_truth.sql ===
-- V14: Delivery truth — smarter ETA history, zone surge rules, free-delivery tiers

CREATE TABLE order_eta_snapshots (
    id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id                BIGINT        NOT NULL,
    eta_minutes             INT           NOT NULL,
    eta_at                  TIMESTAMP     NOT NULL,
    confidence_low_minutes  INT           NULL,
    confidence_high_minutes INT           NULL,
    traffic_factor          DOUBLE        NULL,
    surge_multiplier        DOUBLE        NULL,
    factors_summary         VARCHAR(500),
    recorded_at             TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_eta_snapshot_order FOREIGN KEY (order_id) REFERENCES orders(id),
    INDEX idx_eta_snapshot_order (order_id, recorded_at DESC)
);

CREATE TABLE zone_surge_rules (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    zone_id          BIGINT        NOT NULL,
    day_of_week      INT           NULL,
    start_hour       INT           NOT NULL,
    end_hour         INT           NOT NULL,
    surge_multiplier DOUBLE        NOT NULL DEFAULT 1.0,
    is_active        BOOLEAN       NOT NULL DEFAULT TRUE,
    CONSTRAINT fk_surge_zone FOREIGN KEY (zone_id) REFERENCES delivery_zones(id),
    INDEX idx_surge_zone (zone_id, is_active)
);

ALTER TABLE delivery_zones
    ADD COLUMN free_delivery_above DOUBLE NULL;

-- Peak-hour surge for Bangalore Central (zone id 1 from V13 seed)
INSERT INTO zone_surge_rules (zone_id, day_of_week, start_hour, end_hour, surge_multiplier)
VALUES (1, NULL, 12, 14, 1.25),
       (1, NULL, 19, 22, 1.35);

-- === V15__promotions_engine.sql ===
-- V15: Promotions engine — campaign rules, usage tracking, banner scheduling

ALTER TABLE promotion_campaigns
    ADD COLUMN restaurant_id BIGINT NULL,
    ADD COLUMN max_discount_amount DOUBLE NULL,
    ADD COLUMN flat_discount_amount DOUBLE NULL,
    ADD COLUMN free_delivery BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN priority INT NOT NULL DEFAULT 0,
    ADD COLUMN usage_limit INT NULL,
    ADD COLUMN per_user_limit INT NULL DEFAULT 1,
    ADD CONSTRAINT fk_campaign_restaurant FOREIGN KEY (restaurant_id) REFERENCES restaurants(id);

CREATE TABLE campaign_usages (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    campaign_id BIGINT        NOT NULL,
    customer_id BIGINT        NOT NULL,
    order_id    BIGINT        NULL,
    used_at     TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_usage_campaign FOREIGN KEY (campaign_id) REFERENCES promotion_campaigns(id),
    CONSTRAINT fk_usage_customer FOREIGN KEY (customer_id) REFERENCES customers(id),
    CONSTRAINT fk_usage_order FOREIGN KEY (order_id) REFERENCES orders(id),
    INDEX idx_usage_campaign (campaign_id),
    INDEX idx_usage_customer (campaign_id, customer_id)
);

-- Sample platform-wide lunch campaign
INSERT INTO promotion_campaigns (name, campaign_type, description, discount_percent, min_order_amount,
                                 is_active, priority, free_delivery, starts_at, ends_at)
VALUES ('Lunch Rush', 'PERCENT_OFF', '10% off lunch orders above ₹200', 10.0, 200.0, TRUE, 10, FALSE,
        NOW(), DATE_ADD(NOW(), INTERVAL 90 DAY));

-- === V16__scale_operations.sql ===
-- V16: Scale ops — settlement automation runs, rider delivery batching

CREATE TABLE settlement_runs (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_type            VARCHAR(30)   NOT NULL,
    status              VARCHAR(20)   NOT NULL DEFAULT 'RUNNING',
    restaurants_settled INT           NOT NULL DEFAULT 0,
    agents_settled      INT           NOT NULL DEFAULT 0,
    total_amount        DOUBLE        NOT NULL DEFAULT 0,
    started_at          TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at        TIMESTAMP     NULL,
    notes               TEXT
);

CREATE TABLE rider_delivery_batches (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    agent_id     BIGINT        NOT NULL,
    status       VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',
    created_at   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP     NULL,
    CONSTRAINT fk_batch_agent FOREIGN KEY (agent_id) REFERENCES delivery_agents(id),
    INDEX idx_batch_agent (agent_id, status)
);

CREATE TABLE rider_delivery_batch_orders (
    batch_id         BIGINT NOT NULL,
    order_id         BIGINT NOT NULL,
    sequence_number  INT    NOT NULL,
    PRIMARY KEY (batch_id, order_id),
    CONSTRAINT fk_batch_order_batch FOREIGN KEY (batch_id) REFERENCES rider_delivery_batches(id),
    CONSTRAINT fk_batch_order_order FOREIGN KEY (order_id) REFERENCES orders(id),
    UNIQUE KEY uk_batch_order (order_id)
);

-- === V17__trust_and_compliance.sql ===
-- V17: Trust & compliance — GST invoice PDFs, delivery proof of handover,
--      ETA accuracy reporting support, review moderation query support.
--
-- Scope notes:
--  * order_invoices already exists (V13). This migration only adds the PDF
--    artifact + email audit columns; no invoice data is rewritten.
--  * reviews.moderation_status / reviews.owner_response already exist (V13).
--    Only the supporting index is added here so public reads can filter by
--    moderation state without a full scan.
--  * orders.estimated_delivery_at (promised) and orders.delivered_at (actual)
--    already exist (V1). The ETA accuracy metric is derived from those two
--    columns, so no new order columns are required — only an index.

-- ---------------------------------------------------------------------------
-- 1. GST invoice PDF artifacts and email delivery audit
-- ---------------------------------------------------------------------------
-- pdf_storage_key   : object-storage key of the rendered PDF (presigned on read)
-- pdf_generated_at  : when the PDF was rendered; NULL means "JSON invoice only"
-- emailed_at        : when the invoice email was accepted by the mail sender
-- email_recipient   : address the invoice was sent to (audit trail; the customer
--                     email may change later, so it is snapshotted here)
-- email_attempts    : number of send attempts, used to stop retry loops
ALTER TABLE order_invoices
    ADD COLUMN pdf_storage_key  VARCHAR(512) NULL,
    ADD COLUMN pdf_generated_at TIMESTAMP    NULL,
    ADD COLUMN emailed_at       TIMESTAMP    NULL,
    ADD COLUMN email_recipient  VARCHAR(255) NULL,
    ADD COLUMN email_attempts   INT          NOT NULL DEFAULT 0;

-- Lets the invoice mailer poll for "PDF ready but not yet emailed" rows.
ALTER TABLE order_invoices
    ADD INDEX idx_invoice_email_pending (emailed_at, pdf_generated_at);

-- ---------------------------------------------------------------------------
-- 2. Delivery proof of handover (OTP + photo)
-- ---------------------------------------------------------------------------
-- One row per order. Created when the rider requests an OTP (or uploads a
-- photo) and completed when the handover is verified. Kept in a separate table
-- rather than on `orders` so the hot orders row stays narrow and the OTP hash
-- can be dropped independently for data-retention purposes.
--
-- otp_code_hash : hash of the 6-digit OTP shown to the customer. The plaintext
--                 OTP is never stored.
-- proof_type    : OTP | PHOTO | OTP_AND_PHOTO | SKIPPED (contactless/absent)
-- status        : PENDING | VERIFIED | FAILED | SKIPPED
CREATE TABLE order_delivery_proofs (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id            BIGINT       NOT NULL,
    agent_id            BIGINT       NULL,
    proof_type          VARCHAR(20)  NOT NULL DEFAULT 'OTP',
    status              VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    otp_code_hash       VARCHAR(128) NULL,
    otp_issued_at       TIMESTAMP    NULL,
    otp_expires_at      TIMESTAMP    NULL,
    otp_attempts        INT          NOT NULL DEFAULT 0,
    verified_at         TIMESTAMP    NULL,
    photo_storage_key   VARCHAR(512) NULL,
    photo_uploaded_at   TIMESTAMP    NULL,
    recipient_name      VARCHAR(120) NULL,
    capture_latitude    DOUBLE       NULL,
    capture_longitude   DOUBLE       NULL,
    notes               TEXT         NULL,
    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_delivery_proof_order FOREIGN KEY (order_id) REFERENCES orders(id),
    CONSTRAINT fk_delivery_proof_agent FOREIGN KEY (agent_id) REFERENCES delivery_agents(id),
    UNIQUE KEY uk_delivery_proof_order (order_id),
    INDEX idx_delivery_proof_agent (agent_id, status),
    INDEX idx_delivery_proof_status (status, created_at)
);

-- ---------------------------------------------------------------------------
-- 3. ETA accuracy reporting support
-- ---------------------------------------------------------------------------
-- The ops dashboard compares estimated_delivery_at against delivered_at over a
-- recent window. This index keeps that aggregation off a full table scan.
ALTER TABLE orders
    ADD INDEX idx_order_eta_accuracy (delivered_at, estimated_delivery_at);

-- ---------------------------------------------------------------------------
-- 4. Review moderation query support
-- ---------------------------------------------------------------------------
-- Public restaurant review reads filter to APPROVED; the admin moderation
-- queue filters to PENDING ordered by age.
ALTER TABLE reviews
    ADD INDEX idx_review_restaurant_moderation (restaurant_id, moderation_status),
    ADD INDEX idx_review_moderation_queue (moderation_status, created_at);

-- ---------------------------------------------------------------------------
-- 5. Fraud detection counting support
-- ---------------------------------------------------------------------------
-- Every guarded endpoint (register, login, order create) runs two COUNT queries
-- on fraud_events in the request path, so these must be covering:
--
--   ... WHERE event_type = ? AND ip_address = ?          AND created_at > ?
--   ... WHERE event_type = ? AND device_fingerprint = ?  AND created_at > ?
--
-- Column order matters. Both equality predicates come first so they narrow the
-- range, and created_at comes last so the sliding window is an index range scan
-- rather than a filter over every row ever recorded for that source.
--
-- The pre-existing idx_fraud_type (event_type) and idx_fraud_customer
-- (customer_id) from V13 are left in place: idx_fraud_type is now a redundant
-- prefix of both new indexes, but dropping it is a separate cleanup and would
-- make this migration harder to reverse.
--
-- Names are referenced in the FraudEventRepository Javadoc; keep them in sync.
ALTER TABLE fraud_events
    ADD INDEX idx_fraud_ip_type_created (event_type, ip_address, created_at),
    ADD INDEX idx_fraud_fingerprint_type_created (event_type, device_fingerprint, created_at);

-- === V18__order_status_scheduled.sql ===
-- Align orders.status with Order.OrderStatus (includes SCHEDULED for scheduled orders).
-- Some environments were created with a MySQL ENUM that omitted SCHEDULED; use VARCHAR to match V1 baseline.
ALTER TABLE orders
    MODIFY COLUMN status VARCHAR(20) NOT NULL DEFAULT 'PLACED';

-- === V19__database_optimizations.sql ===
-- V19: Database optimizations — schema already applied manually
--
-- The materialized view tables (restaurant_ratings_summary,
-- restaurant_order_stats) and covering indexes on orders were already
-- present in the database before this migration was tracked by Flyway.
-- This version is intentionally a no-op so Flyway can advance the schema
-- history without attempting to recreate existing objects.
--
-- If partitioning is still desired, implement it in a follow-up migration
-- using pt-online-schema-change or gh-ost.

-- === V21__create_inventory_alerts.sql ===
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

-- === V23__create_user_referral_codes.sql ===
-- V23: Create user_referral_codes table for User.referralCodes element collection
CREATE TABLE user_referral_codes (
    user_id BIGINT NOT NULL,
    code VARCHAR(20) NOT NULL,
    PRIMARY KEY (user_id, code),
    CONSTRAINT fk_referral_code_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- === V24__create_gift_cards.sql ===
-- V24: Create gift_cards table for GiftCard entity
CREATE TABLE gift_cards (
    id BIGINT NOT NULL AUTO_INCREMENT,
    code VARCHAR(20) NOT NULL UNIQUE,
    amount DOUBLE NOT NULL,
    balance DOUBLE NOT NULL,
    status VARCHAR(20) NOT NULL,
    purchased_by BIGINT NULL,
    recipient_email VARCHAR(100) NULL,
    recipient_name VARCHAR(100) NULL,
    message TEXT NULL,
    redeemed_by BIGINT NULL,
    redeemed_at DATETIME NULL,
    expires_at DATETIME NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE gift_cards
    ADD CONSTRAINT fk_gift_card_purchaser FOREIGN KEY (purchased_by) REFERENCES users(id) ON DELETE SET NULL,
    ADD CONSTRAINT fk_gift_card_redeemer FOREIGN KEY (redeemed_by) REFERENCES users(id) ON DELETE SET NULL;

-- === V25__create_dynamic_pricing_rules.sql ===
-- V25: Create dynamic_pricing_rules table for DynamicPricingRule entity
-- IF NOT EXISTS: safe when V24 previously shipped duplicate DDL (staging repair + re-apply)
CREATE TABLE IF NOT EXISTS dynamic_pricing_rules (
    id BIGINT NOT NULL AUTO_INCREMENT,
    restaurant_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    type VARCHAR(20) NOT NULL,
    active TINYINT(1) NOT NULL DEFAULT 1,
    start_time TIME NOT NULL,
    end_time TIME NOT NULL,
    day_of_week INT NOT NULL,
    discount_percent DOUBLE NOT NULL DEFAULT 0.0,
    surge_percent DOUBLE NOT NULL DEFAULT 0.0,
    min_order_amount DOUBLE NOT NULL DEFAULT 0.0,
    max_discount_amount DOUBLE NOT NULL DEFAULT 0.0,
    priority INT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_dynamic_pricing_restaurant FOREIGN KEY (restaurant_id) REFERENCES restaurants(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- === V26__apply_deferred_schema_columns.sql ===
-- V26: Apply schema columns that V20/V22 recorded as no-ops (local-only manual DDL).
-- Idempotent for environments where columns already exist.

SET @schema_name = DATABASE();

-- restaurants.virtual_brand_name
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'restaurants' AND COLUMN_NAME = 'virtual_brand_name') = 0,
    'ALTER TABLE restaurants ADD COLUMN virtual_brand_name VARCHAR(100) NULL',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- users.referrer_id
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'users' AND COLUMN_NAME = 'referrer_id') = 0,
    'ALTER TABLE users ADD COLUMN referrer_id BIGINT NULL',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- membership_plans columns
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'membership_plans' AND COLUMN_NAME = 'max_discount_percent') = 0,
    'ALTER TABLE membership_plans ADD COLUMN max_discount_percent DOUBLE NOT NULL DEFAULT 0.0',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'membership_plans' AND COLUMN_NAME = 'referral_bonus_percent') = 0,
    'ALTER TABLE membership_plans ADD COLUMN referral_bonus_percent DOUBLE NOT NULL DEFAULT 0.0',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'membership_plans' AND COLUMN_NAME = 'referral_max_per_month') = 0,
    'ALTER TABLE membership_plans ADD COLUMN referral_max_per_month INT NOT NULL DEFAULT 0',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- === V2__platform_operations.sql ===
-- Platform/Operations features:
--   * Multi-city/Region Support        -> city_configs
--   * Dark Kitchen Onboarding          -> restaurants.onboarding_status
--   * Fraud Dashboard review queue     -> fraud_review_queue
--   * Automated Dispute Resolution     -> disputes
--   * Promotion Engine (BOGO/segment)  -> promotion_campaigns extension columns
--   * Affiliate/Referral Program       -> affiliate_codes, affiliate_referrals
--   * White-label Solution             -> tenants, restaurants.tenant_id

-- ── Multi-city/Region Support ────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS city_configs (
    id                         BIGINT AUTO_INCREMENT PRIMARY KEY,
    city                       VARCHAR(100) NOT NULL,
    display_name               VARCHAR(120) NOT NULL,
    currency                   VARCHAR(10)  NOT NULL DEFAULT 'INR',
    timezone                   VARCHAR(60)  NOT NULL DEFAULT 'Asia/Kolkata',
    supported_payment_methods  VARCHAR(255),
    default_min_order_amount   DOUBLE       NOT NULL DEFAULT 0.0,
    is_serviceable             BOOLEAN      NOT NULL DEFAULT TRUE,
    is_active                  BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at                 TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                 TIMESTAMP    NULL,
    UNIQUE KEY uk_city_configs_city (city)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ── Dark Kitchen Onboarding ─────────────────────────────────────────────────
ALTER TABLE restaurants
    ADD COLUMN onboarding_status VARCHAR(30) NOT NULL DEFAULT 'APPROVED',
    ADD COLUMN onboarding_rejection_reason VARCHAR(255) NULL;

-- ── Fraud Dashboard manual review queue ─────────────────────────────────────
CREATE TABLE IF NOT EXISTS fraud_review_queue (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    fraud_event_id  BIGINT NOT NULL,
    customer_id     BIGINT NULL,
    action          VARCHAR(30) NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    notes           VARCHAR(500) NULL,
    reviewed_by     BIGINT NULL,
    reviewed_at     TIMESTAMP NULL,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_fraud_review_event FOREIGN KEY (fraud_event_id) REFERENCES fraud_events(id) ON DELETE CASCADE,
    CONSTRAINT fk_fraud_review_customer FOREIGN KEY (customer_id) REFERENCES customers(id) ON DELETE SET NULL,
    CONSTRAINT fk_fraud_review_user FOREIGN KEY (reviewed_by) REFERENCES users(id) ON DELETE SET NULL,
    UNIQUE KEY uk_fraud_review_event (fraud_event_id),
    INDEX idx_fraud_review_status (status),
    INDEX idx_fraud_review_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ── Automated Dispute Resolution ────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS disputes (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id           BIGINT NOT NULL,
    type               VARCHAR(20) NOT NULL,
    status             VARCHAR(20) NOT NULL,
    customer_evidence  TEXT,
    rider_evidence     TEXT,
    restaurant_evidence TEXT,
    resolution_notes   TEXT,
    resolution         VARCHAR(20) NULL,
    refund_amount      DOUBLE NULL,
    resolved_by        BIGINT NULL,
    resolved_at        TIMESTAMP NULL,
    created_at         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_dispute_order FOREIGN KEY (order_id) REFERENCES orders(id) ON DELETE CASCADE,
    CONSTRAINT fk_dispute_resolved_by FOREIGN KEY (resolved_by) REFERENCES users(id) ON DELETE SET NULL,
    UNIQUE KEY uk_dispute_order (order_id),
    INDEX idx_dispute_status (status),
    INDEX idx_dispute_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ── Promotion Engine: buy X get Y + user segment ────────────────────────────
ALTER TABLE promotion_campaigns
    ADD COLUMN buy_quantity INT NULL,
    ADD COLUMN get_quantity INT NULL,
    ADD COLUMN get_discount_percent DOUBLE NULL,
    ADD COLUMN target_segment VARCHAR(30) NULL,
    ADD COLUMN applicable_menu_item_id BIGINT NULL;

-- ── Affiliate/Referral Program ──────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS affiliate_codes (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    code          VARCHAR(40) NOT NULL,
    name          VARCHAR(120) NOT NULL,
    channel       VARCHAR(40) NULL,
    reward_amount DOUBLE NOT NULL DEFAULT 0.0,
    is_active     BOOLEAN NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_affiliate_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS affiliate_referrals (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    affiliate_code_id BIGINT NOT NULL,
    customer_id      BIGINT NOT NULL,
    reward_amount    DOUBLE NOT NULL DEFAULT 0.0,
    status           VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_affiliate_referral_code FOREIGN KEY (affiliate_code_id) REFERENCES affiliate_codes(id) ON DELETE CASCADE,
    CONSTRAINT fk_affiliate_referral_customer FOREIGN KEY (customer_id) REFERENCES customers(id) ON DELETE CASCADE,
    INDEX idx_affiliate_referral_code (affiliate_code_id),
    INDEX idx_affiliate_referral_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ── White-label Solution (tenant isolation) ─────────────────────────────────
CREATE TABLE IF NOT EXISTS tenants (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(120) NOT NULL,
    domain      VARCHAR(120) NOT NULL,
    brand_name  VARCHAR(120) NULL,
    logo_url    VARCHAR(500) NULL,
    theme_color VARCHAR(30)  NULL,
    currency    VARCHAR(10)  NOT NULL DEFAULT 'INR',
    is_active   BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP    NULL,
    UNIQUE KEY uk_tenants_domain (domain)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE restaurants
    ADD COLUMN tenant_id BIGINT NULL,
    ADD CONSTRAINT fk_restaurant_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE SET NULL;

-- === V27__api_keys.sql ===
-- Security: partner API key management for programmatic integrations.

-- ── API keys ─────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS api_keys (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(120) NOT NULL,
    key_prefix VARCHAR(12) NOT NULL,
    key_hash CHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    partner_id BIGINT,
    scopes VARCHAR(255),
    expires_at DATETIME(6),
    last_used_at DATETIME(6),
    created_at DATETIME(6) NOT NULL,
    revoked_at DATETIME(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_api_keys_prefix (key_prefix),
    INDEX idx_api_keys_status (status),
    INDEX idx_api_keys_partner (partner_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- === V27__missing_entity_tables.sql ===
-- Tables referenced by JPA entities that were never created by any earlier
-- migration. The consolidated V1__baseline_schema.sql was assembled from the
-- original V1–V26 series and dropped these tables during the merge.
--
-- All statements are idempotent (`IF NOT EXISTS`) so this migration is safe on
-- existing environments where the tables may already exist (e.g. created by
-- manual DDL or the short-lived V26 async-eventing file):
--   * cities                     -> City entity
--   * group_orders               -> GroupOrder entity
--   * group_order_participants   -> GroupOrder @ElementCollection table
--   * dead_letter_events         -> DeadLetterEvent entity (outbox DLQ)
--   * restaurant_ratings_summary -> MaterializedViewRefreshService target
--   * restaurant_order_stats     -> MaterializedViewRefreshService target

-- ── Cities ────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS cities (
    id                         BIGINT NOT NULL AUTO_INCREMENT,
    name                       VARCHAR(100) NOT NULL,
    country                    VARCHAR(100),
    currency                   VARCHAR(50),
    timezone                   VARCHAR(50),
    supported_payment_methods  VARCHAR(50),
    default_min_order_amount   DOUBLE,
    is_serviceable             BIT(1) DEFAULT 0,
    is_active                  BIT(1) DEFAULT 0,
    created_at                 DATETIME(6) NOT NULL,
    updated_at                 DATETIME(6),
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ── Group Orders ──────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS group_orders (
    id                       BIGINT NOT NULL AUTO_INCREMENT,
    order_number             VARCHAR(255) NOT NULL,
    restaurant_id            BIGINT NOT NULL,
    primary_customer_id      BIGINT NOT NULL,
    status                   VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    subtotal                 DOUBLE,
    delivery_fee             DOUBLE,
    tax_amount               DOUBLE,
    discount_amount          DOUBLE,
    total_amount             DOUBLE,
    tip_amount               DOUBLE,
    special_instructions     VARCHAR(255),
    contactless_delivery     BIT(1) DEFAULT 0,
    created_at               DATETIME(6) NOT NULL,
    confirmed_at             DATETIME(6),
    cancelled_at             DATETIME(6),
    cancellation_reason      VARCHAR(255),
    updated_at               DATETIME(6) NOT NULL,
    payment_method           VARCHAR(30),
    loyalty_points_redeemed  INT DEFAULT 0,
    wallet_amount_used       DOUBLE DEFAULT 0.0,
    PRIMARY KEY (id),
    CONSTRAINT fk_group_order_restaurant FOREIGN KEY (restaurant_id) REFERENCES restaurants(id),
    CONSTRAINT fk_group_order_customer FOREIGN KEY (primary_customer_id) REFERENCES customers(id),
    INDEX idx_group_order_status (status),
    INDEX idx_group_order_created (created_at),
    INDEX idx_group_order_restaurant_status (restaurant_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Group order participants (GroupOrder @ElementCollection)
CREATE TABLE IF NOT EXISTS group_order_participants (
    group_order_id BIGINT NOT NULL,
    customer_id    BIGINT NOT NULL,
    PRIMARY KEY (group_order_id, customer_id),
    CONSTRAINT fk_gop_group_order FOREIGN KEY (group_order_id) REFERENCES group_orders(id) ON DELETE CASCADE,
    INDEX idx_gop_customer (customer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ── Dead letter queue (outbox failures) ───────────────────────────────────
CREATE TABLE IF NOT EXISTS dead_letter_events (
    id BIGINT NOT NULL AUTO_INCREMENT,
    event_type VARCHAR(80) NOT NULL,
    aggregate_type VARCHAR(50) NOT NULL,
    aggregate_id BIGINT NOT NULL,
    payload TEXT NOT NULL,
    last_error VARCHAR(1000),
    retry_count INT NOT NULL DEFAULT 0,
    source VARCHAR(20),
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at DATETIME(6) NOT NULL,
    requeued_at DATETIME(6),
    PRIMARY KEY (id),
    INDEX idx_dlq_status_created (status, created_at),
    INDEX idx_dlq_aggregate (aggregate_type, aggregate_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ── Materialized-view summary tables (MaterializedViewRefreshService) ─────
-- Refreshed on a schedule by MaterializedViewRefreshService; referenced via
-- native INSERT ... ON DUPLICATE KEY UPDATE, so they must exist on fresh DBs.
-- Idempotent: existing environments that created them manually keep working.
CREATE TABLE IF NOT EXISTS restaurant_ratings_summary (
    restaurant_id      BIGINT NOT NULL,
    average_rating     DOUBLE NOT NULL DEFAULT 0,
    total_reviews      INT NOT NULL DEFAULT 0,
    positive_reviews   INT NOT NULL DEFAULT 0,
    last_calculated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (restaurant_id),
    CONSTRAINT fk_rrs_restaurant FOREIGN KEY (restaurant_id) REFERENCES restaurants(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS restaurant_order_stats (
    restaurant_id      BIGINT NOT NULL,
    total_orders       INT NOT NULL DEFAULT 0,
    delivered_orders   INT NOT NULL DEFAULT 0,
    cancelled_orders   INT NOT NULL DEFAULT 0,
    total_revenue      DOUBLE NOT NULL DEFAULT 0,
    avg_order_value    DOUBLE NOT NULL DEFAULT 0,
    last_calculated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (restaurant_id),
    CONSTRAINT fk_ros_restaurant FOREIGN KEY (restaurant_id) REFERENCES restaurants(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- V29__fix_restaurant_onboarding_rejection_reason.sql
-- Some environments have V2 recorded in flyway_schema_history without the
-- ALTER TABLE actually taking effect, leaving restaurants missing the
-- onboarding_rejection_reason column (app fails with
-- "Unknown column 'r1_0.onboarding_rejection_reason'"). Add it idempotently so
-- every database converges whether or not the column already exists.

SET @col_exists = (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'restaurants'
      AND COLUMN_NAME = 'onboarding_rejection_reason'
);

SET @ddl = IF(@col_exists = 0,
    'ALTER TABLE restaurants ADD COLUMN onboarding_rejection_reason VARCHAR(255) NULL',
    'SELECT 1');

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- V30__fix_restaurant_onboarding_status_tenant.sql
-- Same root cause as V29: on some databases V2 is recorded in
-- flyway_schema_history without the ALTER TABLE taking effect, so the
-- restaurants table is missing onboarding_status and tenant_id. Add both
-- idempotently (information_schema + prepared statements) so every database
-- converges whether or not the columns already exist.

SET @col_exists = (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'restaurants'
      AND COLUMN_NAME = 'onboarding_status'
);

SET @ddl = IF(@col_exists = 0,
    'ALTER TABLE restaurants ADD COLUMN onboarding_status VARCHAR(30) NOT NULL DEFAULT ''APPROVED''',
    'SELECT 1');

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col_exists = (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'restaurants'
      AND COLUMN_NAME = 'tenant_id'
);

SET @ddl = IF(@col_exists = 0,
    'ALTER TABLE restaurants ADD COLUMN tenant_id BIGINT NULL',
    'SELECT 1');

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- V31__add_totp_mfa.sql
-- Adds TOTP (RFC 6238) multi-factor authentication support for ADMIN and
-- RESTAURANT_OWNER accounts.
--
--   totp_secret  : base32-encoded TOTP secret; NULL means MFA not enrolled
--   totp_enabled : whether the account requires a second factor at login
--
-- Both columns are added idempotently (information_schema guards + prepared
-- statements) so the migration is safe on databases where they may already
-- exist from a manual hotfix.

SET @schema_name = DATABASE();

-- users.totp_secret
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'users' AND COLUMN_NAME = 'totp_secret') = 0,
    'ALTER TABLE users ADD COLUMN totp_secret VARCHAR(64) NULL',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- users.totp_enabled
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'users' AND COLUMN_NAME = 'totp_enabled') = 0,
    'ALTER TABLE users ADD COLUMN totp_enabled TINYINT(1) NOT NULL DEFAULT 0',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- V32__audit_events.sql
-- Immutable audit trail for sensitive operations (FEATURE #2).
--
-- audit_events is append-only: nothing in the application updates or deletes a row.
-- Each row captures the actor, action, target resource and before/after state so
-- that sensitive operations (refunds, logins, admin state changes) can be reviewed.
--
-- The table and both indexes are created idempotently (information_schema guards +
-- prepared statements) so the migration is safe on databases where they may already
-- exist from a manual hotfix.

SET @schema_name = DATABASE();

-- audit_events
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.TABLES
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'audit_events') = 0,
    'CREATE TABLE audit_events (
        id BIGINT NOT NULL AUTO_INCREMENT,
        actor_id BIGINT NULL,
        actor_role VARCHAR(30) NULL,
        action VARCHAR(80) NOT NULL,
        resource_type VARCHAR(80) NOT NULL,
        resource_id VARCHAR(100) NULL,
        old_state TEXT NULL,
        new_state TEXT NULL,
        ip_address VARCHAR(45) NULL,
        trace_id VARCHAR(64) NULL,
        request_id VARCHAR(64) NULL,
        created_at DATETIME(6) NOT NULL,
        PRIMARY KEY (id),
        INDEX idx_audit_action_created (action, created_at),
        INDEX idx_audit_resource (resource_type, resource_id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- idx_audit_action_created (guarded in case the table pre-existed without indexes)
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'audit_events'
       AND INDEX_NAME = 'idx_audit_action_created') = 0,
    'ALTER TABLE audit_events ADD INDEX idx_audit_action_created (action, created_at)',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- idx_audit_resource
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'audit_events'
       AND INDEX_NAME = 'idx_audit_resource') = 0,
    'ALTER TABLE audit_events ADD INDEX idx_audit_resource (resource_type, resource_id)',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- V37__performance_indexes.sql
-- Index audit (feature #15): adds composite indexes for the most common
-- production query patterns that were previously single-column or missing:
--
--   1. orders(status, created_at)         -- kitchen queue, admin status dashboards,
--                                            scheduled-order dispatch scans
--   2. orders(customer_id, created_at)    -- customer order-history listing (paged)
--   3. order_items(menu_item_id)          -- menu-item sales analytics
--   4. fraud_events(created_at)           -- sliding-window cleanup / retention scans
--
-- All statements are idempotent (information_schema guards + prepared
-- statements), matching the V31 style, so they are safe on databases where
-- the index may already exist from a manual hotfix.

SET @schema_name = DATABASE();

-- orders(status, created_at)
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'orders'
       AND INDEX_NAME = 'idx_order_status_created') = 0,
    'CREATE INDEX idx_order_status_created ON orders(status, created_at)',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- orders(customer_id, created_at)
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'orders'
       AND INDEX_NAME = 'idx_order_customer_created') = 0,
    'CREATE INDEX idx_order_customer_created ON orders(customer_id, created_at)',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- order_items(menu_item_id)
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'order_items'
       AND INDEX_NAME = 'idx_order_item_menu_item') = 0,
    'CREATE INDEX idx_order_item_menu_item ON order_items(menu_item_id)',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- fraud_events(created_at)
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'fraud_events'
       AND INDEX_NAME = 'idx_fraud_created') = 0,
    'CREATE INDEX idx_fraud_created ON fraud_events(created_at)',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- V38__compliance_tables.sql
-- DPDP/GDPR support tables (FEATURE #16).
--
-- consent_records: one row per (user, purpose) tracking the latest grant/revoke of
--   marketing / notification consent. Upserted by ConsentService.
-- data_export_requests: audit trail + payload store for "export my data" requests.
--
-- Both tables are created idempotently (information_schema guards + prepared
-- statements), matching the pattern used by V32__audit_events.

SET @schema_name = DATABASE();

-- consent_records
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.TABLES
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'consent_records') = 0,
    'CREATE TABLE consent_records (
        id BIGINT NOT NULL AUTO_INCREMENT,
        user_id BIGINT NOT NULL,
        purpose VARCHAR(50) NOT NULL,
        granted TINYINT(1) NOT NULL,
        source VARCHAR(20) NULL,
        created_at DATETIME(6) NOT NULL,
        PRIMARY KEY (id),
        UNIQUE KEY uk_consent_user_purpose (user_id, purpose),
        INDEX idx_consent_user (user_id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- data_export_requests
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.TABLES
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'data_export_requests') = 0,
    'CREATE TABLE data_export_requests (
        id BIGINT NOT NULL AUTO_INCREMENT,
        user_id BIGINT NOT NULL,
        requested_at DATETIME(6) NOT NULL,
        status VARCHAR(20) NOT NULL,
        payload_json TEXT NULL,
        completed_at DATETIME(6) NULL,
        PRIMARY KEY (id),
        INDEX idx_export_user_status (user_id, status, completed_at),
        INDEX idx_export_requested_at (requested_at)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- V39__experiment_exposures.sql
-- A/B experiment exposure log (FEATURE #7).
--
-- One row per (experiment, user) — the first time a user was assigned a variant.
-- The unique constraint makes the table idempotent under concurrent assignment
-- and doubles as the cohort census for lift analysis.

SET @schema_name = DATABASE();

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.TABLES
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'experiment_exposures') = 0,
    'CREATE TABLE experiment_exposures (
        id BIGINT NOT NULL AUTO_INCREMENT,
        experiment_key VARCHAR(80) NOT NULL,
        user_id BIGINT NOT NULL,
        variant VARCHAR(80) NOT NULL,
        bucket INT NOT NULL,
        exposed_at DATETIME(6) NOT NULL,
        PRIMARY KEY (id),
        UNIQUE KEY uk_experiment_user (experiment_key, user_id),
        INDEX idx_experiment_variant (experiment_key, variant),
        INDEX idx_experiment_exposed_at (exposed_at)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- V40__churn_scores.sql
-- Customer churn scoring (FEATURE #12).
--
-- One row per customer holding the latest weekly score, the factors behind it and
-- whether a retention outreach has already been dispatched for the current
-- high-risk episode (prevents repeat spam).

SET @schema_name = DATABASE();

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.TABLES
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'churn_scores') = 0,
    'CREATE TABLE churn_scores (
        id BIGINT NOT NULL AUTO_INCREMENT,
        user_id BIGINT NOT NULL,
        score INT NOT NULL,
        risk_level VARCHAR(10) NOT NULL,
        factors TEXT NULL,
        scored_at DATETIME(6) NOT NULL,
        retention_action_taken TINYINT(1) NOT NULL DEFAULT 0,
        PRIMARY KEY (id),
        UNIQUE KEY uk_churn_user (user_id),
        INDEX idx_churn_score (score),
        INDEX idx_churn_risk_scored (risk_level, scored_at)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- V42__order_fulfillment.sql
-- Adds order fulfillment support for three features:
--   1. Guest checkout   : anonymous device_id-scoped identity + phone/OTP capture
--   2. Curbside pickup  : orders.fulfillment_type = 'PICKUP' (skip delivery fee + rider)
--   3. Gift orders      : pay now, deliver to recipient, optional gift message
--
-- Every orders column is added idempotently (information_schema guards +
-- prepared statements, V31 style) so the migration is safe on databases where
-- the columns may already exist from a manual hotfix. gift_orders is created
-- with CREATE TABLE IF NOT EXISTS for the same reason.

SET @schema_name = DATABASE();

-- orders.fulfillment_type
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'orders' AND COLUMN_NAME = 'fulfillment_type') = 0,
    'ALTER TABLE orders ADD COLUMN fulfillment_type VARCHAR(20) NOT NULL DEFAULT ''DELIVERY''',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- orders.device_id
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'orders' AND COLUMN_NAME = 'device_id') = 0,
    'ALTER TABLE orders ADD COLUMN device_id VARCHAR(64) NULL',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- orders.guest_phone
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'orders' AND COLUMN_NAME = 'guest_phone') = 0,
    'ALTER TABLE orders ADD COLUMN guest_phone VARCHAR(15) NULL',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- orders.gift_message
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'orders' AND COLUMN_NAME = 'gift_message') = 0,
    'ALTER TABLE orders ADD COLUMN gift_message VARCHAR(500) NULL',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- orders.recipient_name
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'orders' AND COLUMN_NAME = 'recipient_name') = 0,
    'ALTER TABLE orders ADD COLUMN recipient_name VARCHAR(100) NULL',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- orders.recipient_phone
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'orders' AND COLUMN_NAME = 'recipient_phone') = 0,
    'ALTER TABLE orders ADD COLUMN recipient_phone VARCHAR(15) NULL',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- gift_orders: one row per gift order, linking the paid order to the recipient
-- details. sender_user_id is the purchasing customer; kept nullable (SET NULL on
-- user deletion) so the gift record survives account removal.
CREATE TABLE IF NOT EXISTS gift_orders (
    id                   BIGINT        AUTO_INCREMENT PRIMARY KEY,
    order_id             BIGINT        NOT NULL UNIQUE,
    sender_user_id       BIGINT        NULL,
    recipient_name       VARCHAR(100)  NULL,
    recipient_phone      VARCHAR(15)   NULL,
    recipient_address_id BIGINT        NULL,
    message              VARCHAR(500)  NULL,
    created_at           DATETIME(6)   NOT NULL,
    CONSTRAINT fk_gift_orders_order  FOREIGN KEY (order_id)        REFERENCES orders(id)  ON DELETE CASCADE,
    CONSTRAINT fk_gift_orders_sender FOREIGN KEY (sender_user_id)  REFERENCES users(id)   ON DELETE SET NULL,
    INDEX idx_gift_orders_sender (sender_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- V43__group_orders.sql
-- Adds group ordering and bill splitting tables:
--   1. group_orders        : one row per group order (host + status + timestamps)
--   2. group_order_members : invited/joined users, invite phone, contribution
--
-- V28 already creates a legacy-shaped `group_orders` table. The GroupOrder entity
-- has since been redesigned around host/title/placed_at, so this migration
-- EXPANDS the existing table idempotently (add-column-if-missing) instead of
-- relying on CREATE TABLE IF NOT EXISTS, then adds indexes guarded on column
-- existence. Legacy columns are left in place; JPA simply ignores them.

SET @schema_name = DATABASE();

CREATE TABLE IF NOT EXISTS group_orders (
    id              BIGINT        AUTO_INCREMENT PRIMARY KEY,
    host_user_id    BIGINT        NULL,
    title           VARCHAR(100)  NULL,
    status          VARCHAR(30)   NOT NULL DEFAULT 'OPEN',
    created_at      DATETIME(6)   NULL,
    placed_at       DATETIME(6)   NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- host_user_id (nullable here: legacy rows have no host; new writes always set it)
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'group_orders' AND COLUMN_NAME = 'host_user_id') = 0,
    'ALTER TABLE group_orders ADD COLUMN host_user_id BIGINT NULL',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'group_orders' AND COLUMN_NAME = 'title') = 0,
    'ALTER TABLE group_orders ADD COLUMN title VARCHAR(100) NULL',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'group_orders' AND COLUMN_NAME = 'placed_at') = 0,
    'ALTER TABLE group_orders ADD COLUMN placed_at DATETIME(6) NULL',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- Index only when the column it targets exists (fresh installs create it above;
-- databases carrying the legacy shape get it via the ALTERs above).
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'group_orders' AND COLUMN_NAME = 'host_user_id') > 0
    AND (SELECT COUNT(*) FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'group_orders' AND INDEX_NAME = 'idx_group_host') = 0,
    'CREATE INDEX idx_group_host ON group_orders (host_user_id)',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS group_order_members (
    id                   BIGINT        AUTO_INCREMENT PRIMARY KEY,
    group_order_id       BIGINT        NOT NULL,
    user_id              BIGINT        NOT NULL,
    invite_phone         VARCHAR(15)   NULL,
    status               VARCHAR(20)   NOT NULL DEFAULT 'INVITED',
    amount_contribution  DOUBLE        NULL,
    paid                 TINYINT(1)    NOT NULL DEFAULT 0,
    joined_at            DATETIME(6)   NULL,
    UNIQUE KEY uq_group_member (group_order_id, user_id),
    INDEX idx_group_member_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'group_order_members' AND INDEX_NAME = 'idx_group_member_user') = 0,
    'CREATE INDEX idx_group_member_user ON group_order_members (user_id)',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- V44__subscription_plans.sql
-- Adds recurring subscription meal plan support. Customers subscribe to a weekly
-- meal plan (restaurant + items + weekday + delivery time); the scheduler
-- materialises the next due subscription into a real scheduled order.
--
--   subscription_plans       : weekly plan definition (status, schedule, items)
--   subscription_deliveries  : per-instance delivery tracking (PENDING/PLACED/
--                             SKIPPED/FAILED)
--
-- Both tables are added idempotently (information_schema guards) so the
-- migration is safe on databases where they may already exist from a manual
-- hotfix or parallel branch.

SET @schema_name = DATABASE();

-- ==================== subscription_plans ====================

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.TABLES
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'subscription_plans') = 0,
    'CREATE TABLE subscription_plans (
        id BIGINT AUTO_INCREMENT PRIMARY KEY,
        user_id BIGINT NOT NULL,
        restaurant_id BIGINT NOT NULL,
        title VARCHAR(100),
        items_json TEXT,
        weekday VARCHAR(10) NOT NULL COMMENT \'MON/TUE/WED/THU/FRI/SAT/SUN\',
        delivery_time TIME NOT NULL,
        delivery_address_id BIGINT NOT NULL,
        payment_method VARCHAR(30) NOT NULL,
        status VARCHAR(20) NOT NULL DEFAULT \'ACTIVE\' COMMENT \'ACTIVE/PAUSED/CANCELLED\',
        start_date DATE NOT NULL,
        next_delivery_date DATE,
        created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
        INDEX idx_sub_user (user_id),
        INDEX idx_sub_status (status)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ==================== subscription_deliveries ====================

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.TABLES
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'subscription_deliveries') = 0,
    'CREATE TABLE subscription_deliveries (
        id BIGINT AUTO_INCREMENT PRIMARY KEY,
        subscription_plan_id BIGINT NOT NULL,
        order_id BIGINT,
        scheduled_date DATE NOT NULL,
        status VARCHAR(20) NOT NULL DEFAULT \'PENDING\' COMMENT \'PENDING/PLACED/SKIPPED/FAILED\',
        UNIQUE KEY uq_sub_date (subscription_plan_id, scheduled_date),
        INDEX idx_sub_del_plan (subscription_plan_id),
        INDEX idx_sub_del_status (status),
        CONSTRAINT fk_sub_del_plan FOREIGN KEY (subscription_plan_id) REFERENCES subscription_plans(id) ON DELETE CASCADE,
        CONSTRAINT fk_sub_del_order FOREIGN KEY (order_id) REFERENCES orders(id) ON DELETE SET NULL
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- V45__menu_availability.sql
-- Adds dietary flags (is_veg / is_jain) and item-level availability hours
-- (available_from / available_until) to menu_items so menu listings, search
-- results and order validation can be filtered by diet profile and by time of
-- day.
--
-- Every statement is idempotent (information_schema guards + prepared
-- statements), mirroring V31__add_totp_mfa.sql, so the migration is safe on
-- databases where some of these columns may already exist from a manual
-- hotfix. is_veg already exists from V1 on fresh installs; the guard makes the
-- ALTER a no-op there.
--
-- NOTE: the originally requested composite index idx_menu_restaurant_veg
-- (restaurant_id, is_veg) is intentionally NOT created: menu_items has no
-- restaurant_id column (restaurant access is via category_id ->
-- menu_categories.restaurant_id), so that index would fail. The existing
-- idx_menu_item_is_veg index covers the veg filtering path instead.

SET @schema_name = DATABASE();

-- menu_items.is_veg
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'menu_items' AND COLUMN_NAME = 'is_veg') = 0,
    'ALTER TABLE menu_items ADD COLUMN is_veg BOOLEAN NOT NULL DEFAULT FALSE',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- menu_items.is_jain
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'menu_items' AND COLUMN_NAME = 'is_jain') = 0,
    'ALTER TABLE menu_items ADD COLUMN is_jain BOOLEAN NOT NULL DEFAULT FALSE',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- menu_items.available_from
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'menu_items' AND COLUMN_NAME = 'available_from') = 0,
    'ALTER TABLE menu_items ADD COLUMN available_from TIME NULL',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- menu_items.available_until
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'menu_items' AND COLUMN_NAME = 'available_until') = 0,
    'ALTER TABLE menu_items ADD COLUMN available_until TIME NULL',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- V46__menu_versions.sql
-- Adds snapshot-based menu versioning for restaurant owners.
--
--   menu_versions : one row per saved version of a restaurant's menu.
--     - snapshot_json holds a JSON snapshot of the live menu (categories + items)
--       captured when the version was created.
--     - status is DRAFT (not yet published) or PUBLISHED.
--     - publishing a version only flips status/published_at; the live menu that
--       the order flow reads is never modified.
--
-- The CREATE TABLE is guarded with IF NOT EXISTS so the migration is idempotent
-- on databases where the table may already exist from a manual hotfix.

CREATE TABLE IF NOT EXISTS menu_versions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    restaurant_id BIGINT NOT NULL,
    version_number INT NOT NULL,
    label VARCHAR(100),
    snapshot_json MEDIUMTEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    created_at DATETIME(6) NOT NULL,
    published_at DATETIME(6) NULL,
    UNIQUE KEY uq_menu_version (restaurant_id, version_number),
    KEY idx_menu_version_restaurant (restaurant_id)
);

-- V47__delivery_surveys.sql
-- Adds the post-delivery satisfaction survey table.
--
-- A customer can submit one survey per delivered order; responses feed
-- restaurant analytics (average delivery/food/speed ratings).
--
--   order_id        : unique per order (one survey per order)
--   rating_delivery : 1-5, NULL when the customer skipped the question
--   rating_food     : 1-5, NULL when the customer skipped the question
--   rating_speed    : 1-5, NULL when the customer skipped the question
--   comment         : optional free-text feedback
--
-- The table is created with CREATE TABLE IF NOT EXISTS so the migration is
-- idempotent and safe on databases where it may already exist from a manual
-- hotfix (same convention as V42 gift_orders / V43 group_orders).

CREATE TABLE IF NOT EXISTS delivery_surveys (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    order_id        BIGINT       NOT NULL,
    customer_id     BIGINT       NOT NULL,
    rating_delivery INT          NULL,
    rating_food     INT          NULL,
    rating_speed    INT          NULL,
    comment         VARCHAR(1000) NULL,
    submitted_at    DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_survey_order (order_id),
    CONSTRAINT fk_survey_order    FOREIGN KEY (order_id)    REFERENCES orders(id)    ON DELETE CASCADE,
    CONSTRAINT fk_survey_customer FOREIGN KEY (customer_id) REFERENCES customers(id) ON DELETE CASCADE,
    INDEX idx_survey_customer (customer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Optional: speeds up per-restaurant average aggregation (surveys joined to
-- their orders). Guarded so re-runs never fail.
SET @idx_exists = (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'delivery_surveys'
      AND INDEX_NAME = 'idx_survey_restaurant_lookup'
);
SET @sql = IF(
    @idx_exists = 0,
    'ALTER TABLE delivery_surveys ADD INDEX idx_survey_restaurant_lookup (order_id, customer_id)',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- V49__delivery_operations.sql
-- Delivery operations support for three features:
--   1. Agent shifts   : agent_shifts tracks rider working shifts (start/end)
--   2. Incentives     : rider_earnings.bonus_amount / bonus_reason
--   3. COD cash       : agent_cod_wallets tracks cash collected vs deposited
--
-- Both tables are created with CREATE TABLE IF NOT EXISTS and the
-- rider_earnings columns are added idempotently (information_schema guards +
-- prepared statements, V31 style) so the migration is safe on databases where
-- the objects may already exist from a manual hotfix.

SET @schema_name = DATABASE();

-- agent_shifts: one row per rider shift; end_time is populated when the shift
-- is completed (a started shift keeps start_time as a placeholder value).
CREATE TABLE IF NOT EXISTS agent_shifts (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    agent_id   BIGINT      NOT NULL,
    shift_date DATE        NOT NULL,
    start_time TIME        NOT NULL,
    end_time   TIME        NOT NULL,
    status     VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_shift_agent FOREIGN KEY (agent_id) REFERENCES delivery_agents(id) ON DELETE CASCADE,
    INDEX idx_shift_agent (agent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Guarded re-add for the rare case the table pre-existed without the index.
SET @idx_exists = (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = @schema_name
      AND TABLE_NAME = 'agent_shifts'
      AND INDEX_NAME = 'idx_shift_agent'
);
SET @sql = IF(
    @idx_exists = 0,
    'ALTER TABLE agent_shifts ADD INDEX idx_shift_agent (agent_id)',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- rider_earnings.bonus_amount
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'rider_earnings' AND COLUMN_NAME = 'bonus_amount') = 0,
    'ALTER TABLE rider_earnings ADD COLUMN bonus_amount DOUBLE DEFAULT 0.0',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- rider_earnings.bonus_reason
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'rider_earnings' AND COLUMN_NAME = 'bonus_reason') = 0,
    'ALTER TABLE rider_earnings ADD COLUMN bonus_reason VARCHAR(100) NULL',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- agent_cod_wallets: one wallet per agent; cash-on-delivery totals and the
-- last reconciliation timestamp.
CREATE TABLE IF NOT EXISTS agent_cod_wallets (
    id                   BIGINT      NOT NULL AUTO_INCREMENT,
    agent_id             BIGINT      NOT NULL,
    total_cash_collected DOUBLE      NOT NULL DEFAULT 0.0,
    total_cash_deposited DOUBLE      NOT NULL DEFAULT 0.0,
    last_reconciled_at   DATETIME(6) NULL,
    created_at           DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_cod_wallet_agent (agent_id),
    CONSTRAINT fk_agent_cod_wallet_agent FOREIGN KEY (agent_id) REFERENCES delivery_agents(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- V51__saga_events.sql
-- Creates tables for saga pattern implementation: saga_instances and saga_steps
-- to manage long-running transactions like order -> payment -> settlement.

SET @schema_name = DATABASE();

-- saga_instances: tracks the overall saga state
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.TABLES
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'saga_instances') = 0,
    'CREATE TABLE saga_instances (
        id BIGINT PRIMARY KEY AUTO_INCREMENT,
        saga_type VARCHAR(50) NOT NULL,
        saga_id VARCHAR(100) NOT NULL UNIQUE,
        current_step VARCHAR(50),
        status VARCHAR(20) NOT NULL, -- STARTED, STEP_COMPLETED, COMPLETED, COMPENSATING, COMPENSATED, FAILED
        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
        payload JSON, -- serialized input data for the saga
        INDEX idx_saga_type_status (saga_type, status),
        INDEX idx_saga_id (saga_id)
    )',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- saga_steps: tracks each step within a saga, including compensation info
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.TABLES
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'saga_steps') = 0,
    'CREATE TABLE saga_steps (
        id BIGINT PRIMARY KEY AUTO_INCREMENT,
        saga_instance_id BIGINT NOT NULL,
        step_order INT NOT NULL,
        step_name VARCHAR(50) NOT NULL,
        status VARCHAR(20) NOT NULL, -- PENDING, COMPLETED, FAILED, COMPENSATED
        payload JSON, -- input/output data for this step
        compensation_payload JSON, -- data needed to compensate this step
        error_message VARCHAR(1000),
        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
        FOREIGN KEY (saga_instance_id) REFERENCES saga_instances(id) ON DELETE CASCADE,
        UNIQUE KEY uq_saga_instance_step (saga_instance_id, step_order),
        INDEX idx_saga_instance_status (saga_instance_id, status)
    )',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- Index for querying pending steps for processing
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'saga_steps' AND INDEX_NAME = 'idx_saga_steps_pending') = 0,
    'CREATE INDEX idx_saga_steps_pending ON saga_steps(status, step_order)',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- V52__additional_indexes.sql
-- Adds additional indexes for query performance optimization
-- Verified against the actual schema:
--   settlement_runs(started_at), coupon_usages(customer_id), reviews(created_at)
--   orders(restaurant_id, status), payments(order_id, status),
--   menu_items(category_id, available)

SET @schema_name = DATABASE();

-- settlement_runs.started_at index for time-range queries on settlement runs
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'settlement_runs' AND INDEX_NAME = 'idx_settlement_runs_started_at') = 0,
    'CREATE INDEX idx_settlement_runs_started_at ON settlement_runs (started_at)',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- coupon_usages.customer_id index for user-centric coupon queries
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'coupon_usages' AND INDEX_NAME = 'idx_coupon_usages_customer_id') = 0,
    'CREATE INDEX idx_coupon_usages_customer_id ON coupon_usages (customer_id)',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- reviews.created_at index for time-based review queries
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'reviews' AND INDEX_NAME = 'idx_reviews_created_at') = 0,
    'CREATE INDEX idx_reviews_created_at ON reviews (created_at)',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- orders.restaurant_id + status for restaurant order queries
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'orders' AND INDEX_NAME = 'idx_orders_restaurant_status') = 0,
    'CREATE INDEX idx_orders_restaurant_status ON orders (restaurant_id, status)',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- payments.order_id + status for payment lookups
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'payments' AND INDEX_NAME = 'idx_payments_order_status') = 0,
    'CREATE INDEX idx_payments_order_status ON payments (order_id, status)',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- menu_items.category_id + available for menu listing
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'menu_items' AND INDEX_NAME = 'idx_menu_items_category_available') = 0,
    'CREATE INDEX idx_menu_items_category_available ON menu_items (category_id, available)',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- V53__add_membership_tier_level.sql
-- Adds columns to membership_plans that the MembershipPlan entity maps but
-- no earlier migration created. Without these columns, Hibernate's SELECT
-- includes them and queries fail with "Unknown column" SQL errors.
--
-- This manifests as a 500 on /home/feed and /mobile/feed in environments
-- whose DB was built purely from Flyway migrations.

SET @schema_name = DATABASE();

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'membership_plans'
       AND COLUMN_NAME = 'tier_level') = 0,
    'ALTER TABLE membership_plans
     ADD COLUMN tier_level INT NOT NULL DEFAULT 0 COMMENT ''0=Basic, 1=Silver, 2=Gold, 3=Platinum'' AFTER discount_percent',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'membership_plans'
       AND COLUMN_NAME = 'max_discount_percent') = 0,
    'ALTER TABLE membership_plans
     ADD COLUMN max_discount_percent DOUBLE NOT NULL DEFAULT 0.0 AFTER discount_percent',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'membership_plans'
       AND COLUMN_NAME = 'referral_bonus_percent') = 0,
    'ALTER TABLE membership_plans
     ADD COLUMN referral_bonus_percent DOUBLE NOT NULL DEFAULT 0.0 AFTER max_discount_percent',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'membership_plans'
       AND COLUMN_NAME = 'referral_max_per_month') = 0,
    'ALTER TABLE membership_plans
     ADD COLUMN referral_max_per_month INT NOT NULL DEFAULT 0 AFTER referral_bonus_percent',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- =============================================================================
-- V54: Per-domain schemas (Phase 2 — vertical partitioning).
--
-- Creates one schema per owning domain so the same physical MySQL server can
-- host the partitioned monolith. The application still runs on the `bhukkad`
-- schema today; these schemas are the target for the Phase 3 service extraction
-- and let operators pre-provision privileges, backups and read replicas per
-- domain before any data moves.
--
-- Idempotent: CREATE DATABASE IF NOT EXISTS is safe to run on every deploy.
-- =============================================================================

CREATE DATABASE IF NOT EXISTS bhukkad_orders
    CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS bhukkad_payments
    CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS bhukkad_restaurants
    CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS bhukkad_customers
    CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS bhukkad_admin
    CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS bhukkad_delivery
    CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- Grant the application user the same privileges it has on `bhukkad`.
-- GRANT ALL PRIVILEGES ON bhukkad_orders.* TO 'bhukkad_user'@'%';
-- GRANT ALL PRIVILEGES ON bhukkad_payments.* TO 'bhukkad_user'@'%';
-- GRANT ALL PRIVILEGES ON bhukkad_restaurants.* TO 'bhukkad_user'@'%';
-- GRANT ALL PRIVILEGES ON bhukkad_customers.* TO 'bhukkad_user'@'%';
-- GRANT ALL PRIVILEGES ON bhukkad_admin.* TO 'bhukkad_user'@'%';
-- GRANT ALL PRIVILEGES ON bhukkad_delivery.* TO 'bhukkad_user'@'%';
-- FLUSH PRIVILEGES;

-- =============================================================================
-- V55: Event-driven materialized summary tables (Phase 2).
--
-- `trending_dishes` replaces the cross-domain aggregate query
-- (OrderItemRepository.findTrendingByCreatedSince: order_items JOIN menu_items)
-- with a summary table owned by the ANALYTICS domain, fed by the
-- ORDER_ITEMS_SNAPSHOT outbox event. The ADMIN/ANALYTICS domain never joins
-- ORDER tables directly.
-- =============================================================================

CREATE TABLE IF NOT EXISTS trending_dishes (
    menu_item_id      BIGINT       NOT NULL,
    restaurant_id     BIGINT       NOT NULL,
    dish_name         VARCHAR(255) NOT NULL,
    quantity_sold     BIGINT       NOT NULL DEFAULT 0,
    last_order_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (menu_item_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Hot read path for the home feed: rank by quantity sold within the window.
-- Guarded via information_schema so the migration is idempotent (MySQL has no
-- CREATE INDEX IF NOT EXISTS).
SET @index_exists := (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'trending_dishes'
      AND index_name = 'idx_trending_dishes_qty');
SET @ddl := IF(@index_exists = 0,
    'CREATE INDEX idx_trending_dishes_qty ON trending_dishes (quantity_sold DESC)',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- =============================================================================
-- `orders_archive` — partitioned by RANGE COLUMNS on created_at for
-- retention/archival.
--
-- MySQL 8 partitioned InnoDB tables cannot carry foreign keys, so the live
-- `orders` table keeps its FKs and old rows are MOVED here by the archive job
-- (OrderArchiveService). Each partition covers one quarter, which makes the
-- oldest partition DROP cheap (partition pruning) instead of a bulk DELETE.
-- RANGE COLUMNS is used (not TO_DAYS()) because TO_DAYS() is a timezone-
-- dependent function and is rejected by MySQL 8+ partitioning.
-- =============================================================================

CREATE TABLE IF NOT EXISTS orders_archive (
    id                    BIGINT       NOT NULL,
    order_number          VARCHAR(32)  NOT NULL,
    customer_id           BIGINT       NOT NULL,
    restaurant_id         BIGINT       NOT NULL,
    status                VARCHAR(32)  NULL,
    total_amount          DECIMAL(10,2) NULL,
    delivery_address_id   BIGINT       NULL,
    special_instructions  VARCHAR(500) NULL,
    created_at            DATETIME     NOT NULL,
    updated_at            DATETIME     NULL,
    delivered_at          DATETIME     NULL,
    estimated_delivery_at DATETIME     NULL,
    archived_at           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
PARTITION BY RANGE COLUMNS (created_at) (
    PARTITION p2024q1 VALUES LESS THAN ('2024-04-01 00:00:00'),
    PARTITION p2024q2 VALUES LESS THAN ('2024-07-01 00:00:00'),
    PARTITION p2024q3 VALUES LESS THAN ('2024-10-01 00:00:00'),
    PARTITION p2024q4 VALUES LESS THAN ('2025-01-01 00:00:00'),
    PARTITION p2025q1 VALUES LESS THAN ('2025-04-01 00:00:00'),
    PARTITION p2025q2 VALUES LESS THAN ('2025-07-01 00:00:00'),
    PARTITION p2025q3 VALUES LESS THAN ('2025-10-01 00:00:00'),
    PARTITION p2025q4 VALUES LESS THAN ('2026-01-01 00:00:00'),
    PARTITION p2026q1 VALUES LESS THAN ('2026-04-01 00:00:00'),
    PARTITION p_future   VALUES LESS THAN (MAXVALUE)
);

SET @idx1 := (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'orders_archive'
      AND index_name = 'idx_orders_archive_customer');
SET @ddl1 := IF(@idx1 = 0,
    'CREATE INDEX idx_orders_archive_customer ON orders_archive (customer_id)',
    'SELECT 1');
PREPARE stmt1 FROM @ddl1; EXECUTE stmt1; DEALLOCATE PREPARE stmt1;

SET @idx2 := (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'orders_archive'
      AND index_name = 'idx_orders_archive_status');
SET @ddl2 := IF(@idx2 = 0,
    'CREATE INDEX idx_orders_archive_status ON orders_archive (status)',
    'SELECT 1');
PREPARE stmt2 FROM @ddl2; EXECUTE stmt2; DEALLOCATE PREPARE stmt2;

-- V56__fix_group_orders_legacy_columns.sql
-- Fixes the group_orders table shape for the redesigned GroupOrder entity.
--
-- V28 created group_orders with a legacy "orders"-shaped schema whose NOT NULL
-- columns (order_number, restaurant_id, primary_customer_id, updated_at) have
-- no defaults. The current entity only writes host_user_id/title/status/
-- created_at, so every INSERT failed with "Field 'order_number' doesn't have a
-- default value" -> 500 on POST /api/v1/customers/group-orders.
--
-- This migration makes the unused legacy columns nullable and drops their
-- foreign keys so new rows can be inserted and legacy rows can be cleaned up
-- without FK interference. It is idempotent for databases that already ran it.

SET @schema_name = DATABASE();

-- 1) Make legacy NOT NULL columns nullable.
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'group_orders'
       AND COLUMN_NAME = 'order_number' AND IS_NULLABLE = 'NO') > 0,
    'ALTER TABLE group_orders MODIFY COLUMN order_number VARCHAR(255) NULL',
    'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'group_orders'
       AND COLUMN_NAME = 'restaurant_id' AND IS_NULLABLE = 'NO') > 0,
    'ALTER TABLE group_orders MODIFY COLUMN restaurant_id BIGINT NULL',
    'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'group_orders'
       AND COLUMN_NAME = 'primary_customer_id' AND IS_NULLABLE = 'NO') > 0,
    'ALTER TABLE group_orders MODIFY COLUMN primary_customer_id BIGINT NULL',
    'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'group_orders'
       AND COLUMN_NAME = 'updated_at' AND IS_NULLABLE = 'NO') > 0,
    'ALTER TABLE group_orders MODIFY COLUMN updated_at DATETIME(6) NULL',
    'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 2) Drop the legacy foreign keys referencing restaurants/customers (the
--    columns they guard are no longer written by the application).
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'group_orders'
       AND CONSTRAINT_NAME = 'fk_group_order_restaurant') > 0,
    'ALTER TABLE group_orders DROP FOREIGN KEY fk_group_order_restaurant',
    'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'group_orders'
       AND CONSTRAINT_NAME = 'fk_group_order_customer') > 0,
    'ALTER TABLE group_orders DROP FOREIGN KEY fk_group_order_customer',
    'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- Outbox claim-and-process refactor: track when a sweep claimed an event so
-- the recovery sweep can reset events stranded in PROCESSING by a crashed
-- or killed pod back to PENDING.
ALTER TABLE outbox_events
    ADD COLUMN processing_started_at DATETIME(6) NULL AFTER published_at;

-- Phone-first registration: make email, full_name, and password nullable
-- so accounts can be created with only a phone number, then have details
-- appended later. MySQL unique indexes allow multiple NULLs, so the email
-- uniqueness on the existing idx_user_email index is preserved for real values.
ALTER TABLE users MODIFY COLUMN email VARCHAR(100) NULL;
ALTER TABLE users MODIFY COLUMN full_name VARCHAR(100) NULL;
ALTER TABLE users MODIFY COLUMN password VARCHAR(255) NULL;

-- Track phone verification status for the phone-first registration flow.
ALTER TABLE users ADD COLUMN phone_verified BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE users ADD COLUMN phone_verified_at DATETIME(6) NULL;

-- Track whether a phone-first user has completed their profile
-- (added email, full name, password) to gate downstream features.
ALTER TABLE users ADD COLUMN profile_completed BOOLEAN NOT NULL DEFAULT FALSE;

-- Index for the phone-first login / lookup path (phone is already unique
-- via the entity mapping, but an explicit index on the verification
-- query plane is useful for admin and support lookups).
--
-- NOTE: plain CREATE INDEX (no IF NOT EXISTS) — `CREATE INDEX IF NOT EXISTS`
-- requires MySQL 8.0.22+, but the Testcontainers `mysql:8.0` image and some
-- production MySQL 8.0.x releases are older, so the guarded form fails.
-- A duplicate-index failure would indicate the index already exists; run
-- `flyway repair` in that case rather than re-running this migration.
CREATE INDEX idx_user_phone_verified ON users (phone_verified);

-- Persist the coupon code applied to a cart so it survives across get-cart
-- and is available at checkout time. The discount itself is calculated
-- server-side from the coupon rules (OrderPricingServiceImpl), but the
-- cart-level response includes the computed discount so the client can
-- show the reduced total before order placement.

ALTER TABLE carts ADD COLUMN coupon_code VARCHAR(50) NULL AFTER restaurant_id;
CREATE INDEX idx_cart_coupon_code ON carts (coupon_code);

-- V60__high_traffic_indexes.sql
-- High-traffic production indexes. All statements are idempotent (information_schema guards)
-- so the migration is safe to re-run and safe on databases where a hotfix already
-- added the index manually.

SET @schema_name = DATABASE();

-- 1. payments.gateway_order_id — webhook lookup (PaymentServiceImpl.findByGatewayOrderId)
--    is on the critical payment path and previously did a full scan.
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'payments' AND INDEX_NAME = 'idx_payments_gateway_order_id') = 0,
    'CREATE INDEX idx_payments_gateway_order_id ON payments (gateway_order_id)',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- payments.gateway_payment_id — Razorpay payment lookup (refund / verification)
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'payments' AND INDEX_NAME = 'idx_payments_gateway_payment_id') = 0,
    'CREATE INDEX idx_payments_gateway_payment_id ON payments (gateway_payment_id)',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 2. orders(status, scheduled_at) — scheduled-order dispatch
--    `SELECT ... WHERE status='SCHEDULED' AND scheduled_at <= NOW()` runs every 60s
--    on the hot path. Single-column idx_order_scheduled_at is insufficient.
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'orders' AND INDEX_NAME = 'idx_orders_status_scheduled_at') = 0,
    'CREATE INDEX idx_orders_status_scheduled_at ON orders (status, scheduled_at)',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 3. orders(guest_phone) / orders(device_id) — guest checkout lookup
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'orders' AND INDEX_NAME = 'idx_orders_guest_phone') = 0,
    'CREATE INDEX idx_orders_guest_phone ON orders (guest_phone)',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'orders' AND INDEX_NAME = 'idx_orders_device_id') = 0,
    'CREATE INDEX idx_orders_device_id ON orders (device_id)',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 4. outbox_events(status, processing_started_at) — recovery of stale PROCESSING
--    `recoverStaleProcessing` does WHERE status='PROCESSING' AND processing_started_at < NOW()-threshold
--    Previously next_retry_at did not exist; use processing_started_at.
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'outbox_events' AND INDEX_NAME = 'idx_outbox_status_processing') = 0,
    'CREATE INDEX idx_outbox_status_processing ON outbox_events (status, processing_started_at)',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 5. rider_earnings(agent_id, status, created_at) — settlement sweep sorts by createdAt
--    extend existing (agent_id, status) with created_at for cursor pagination.
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'rider_earnings' AND INDEX_NAME = 'idx_rider_earning_agent_status_created') = 0,
    'CREATE INDEX idx_rider_earning_agent_status_created ON rider_earnings (agent_id, status, created_at)',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 6. restaurant_settlements(restaurant_id, status, created_at) — same for settlement history
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'restaurant_settlements' AND INDEX_NAME = 'idx_settlement_restaurant_status_created') = 0,
    'CREATE INDEX idx_settlement_restaurant_status_created ON restaurant_settlements (restaurant_id, status, created_at)',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- V61__covering_high_traffic_indexes.sql
-- Additional covering indexes for high-traffic hot paths missed by V60.
-- All statements idempotent via information_schema.

SET @schema_name = DATABASE();

-- orders(customer_id, status, created_at) — customer history count+sum+history all share this prefix
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'orders' AND INDEX_NAME = 'idx_orders_customer_status_created') = 0,
    'CREATE INDEX idx_orders_customer_status_created ON orders (customer_id, status, created_at)',
    'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- orders(delivery_agent_id, status, created_at) — rider app polls my orders
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'orders' AND INDEX_NAME = 'idx_orders_agent_status_created') = 0,
    'CREATE INDEX idx_orders_agent_status_created ON orders (delivery_agent_id, status, created_at)',
    'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- orders_archive covering for retention
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'orders_archive' AND INDEX_NAME = 'idx_orders_archive_restaurant') = 0,
    'CREATE INDEX idx_orders_archive_restaurant ON orders_archive (restaurant_id)',
    'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'orders_archive' AND INDEX_NAME = 'idx_orders_archive_created') = 0,
    'CREATE INDEX idx_orders_archive_created ON orders_archive (created_at)',
    'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- restaurants(tenant_id, onboarding_status, is_active) — multi-tenant approved list
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'restaurants' AND INDEX_NAME = 'idx_restaurant_tenant_status') = 0,
    'CREATE INDEX idx_restaurant_tenant_status ON restaurants (tenant_id, onboarding_status, is_active)',
    'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- rider_earnings(agent_id, created_at) — without status for history pagination deep pages
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'rider_earnings' AND INDEX_NAME = 'idx_rider_earning_agent_created') = 0,
    'CREATE INDEX idx_rider_earning_agent_created ON rider_earnings (agent_id, created_at)',
    'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- support_tickets / disputes created_at for admin lists
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'support_tickets' AND INDEX_NAME = 'idx_support_created') = 0,
    'CREATE INDEX idx_support_created ON support_tickets (created_at)',
    'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'disputes' AND INDEX_NAME = 'idx_dispute_created') = 0,
    'CREATE INDEX idx_dispute_created ON disputes (created_at)',
    'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- cart_items composite unique to prevent duplicate race
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'cart_items' AND INDEX_NAME = 'uk_cart_item_cart_menu') = 0,
    'CREATE UNIQUE INDEX uk_cart_item_cart_menu ON cart_items (cart_id, menu_item_id)',
    'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- coupons active/restaurant/valid covering
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'coupons' AND INDEX_NAME = 'idx_coupon_active_restaurant_valid') = 0,
    'CREATE INDEX idx_coupon_active_restaurant_valid ON coupons (active, restaurant_id, valid_from, valid_until)',
    'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;


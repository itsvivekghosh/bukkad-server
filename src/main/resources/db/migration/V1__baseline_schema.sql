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

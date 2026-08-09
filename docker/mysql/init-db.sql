-- ============================================
-- Bhukkad Database Initialization Script
-- Creates tables + seeds data
-- ============================================

SET NAMES utf8mb4;
SET CHARACTER SET utf8mb4;

-- Create database
CREATE DATABASE IF NOT EXISTS bhukkad
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE bhukkad;

-- Grant privileges
GRANT ALL PRIVILEGES ON bhukkad.* TO 'bhukkad_user'@'%';
FLUSH PRIVILEGES;

-- ============================================
-- Create Tables
-- ============================================

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

-- ============================================
-- Seed Default Data
-- ============================================

-- Cuisines
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

-- Coupons
INSERT INTO coupons (code, description, discount_type, discount_value, minimum_order_amount, maximum_discount_amount, valid_from, valid_until, usage_limit, used_count, per_user_limit, active) VALUES
('WELCOME50', 'Get 50% off on your first order', 'PERCENTAGE', 50.00, 200.00, 150.00, NOW(), DATE_ADD(NOW(), INTERVAL 1 YEAR), 10000, 0, 1, 1),
('BHUKKAD100', 'Flat Rs.100 off on orders above Rs.500', 'FIXED_AMOUNT', 100.00, 500.00, 100.00, NOW(), DATE_ADD(NOW(), INTERVAL 6 MONTH), 50000, 0, 3, 1),
('FREEDEL', 'Free delivery on any order above Rs.99', 'FIXED_AMOUNT', 40.00, 99.00, 40.00, NOW(), DATE_ADD(NOW(), INTERVAL 3 MONTH), 100000, 0, 5, 1),
('SUPER20', 'Get 20% off on orders above Rs.300', 'PERCENTAGE', 20.00, 300.00, 80.00, NOW(), DATE_ADD(NOW(), INTERVAL 6 MONTH), 50000, 0, 10, 1),
('FEAST30', 'Get 30% off on orders above Rs.600', 'PERCENTAGE', 30.00, 600.00, 200.00, NOW(), DATE_ADD(NOW(), INTERVAL 3 MONTH), 20000, 0, 2, 1),
('FLAT150', 'Flat Rs.150 off on orders above Rs.750', 'FIXED_AMOUNT', 150.00, 750.00, 150.00, NOW(), DATE_ADD(NOW(), INTERVAL 6 MONTH), 30000, 0, 5, 1)
ON DUPLICATE KEY UPDATE code = VALUES(code);

-- ============================================
-- Verification
-- ============================================
SELECT CONCAT('Cuisines inserted: ', COUNT(*)) AS result FROM cuisines;
SELECT CONCAT('Coupons inserted: ', COUNT(*)) AS result FROM coupons;
SELECT 'Bhukkad database initialized successfully!' AS status;
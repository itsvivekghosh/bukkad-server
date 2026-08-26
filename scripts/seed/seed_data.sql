-- ============================================================================
-- Bhukkad Food Delivery — Realistic Seed Data
-- Clears all business tables and loads realistic, testable data for the
-- customer application: users (customers/owners/agents/admin), restaurants,
-- cuisines, menus (categories/items/customizations), zones, cities, coupons
-- and membership plans.
--
-- Apply:  mysql -h 127.0.0.1 -u root -proot bhukkad < scripts/seed/seed_data.sql
-- ============================================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ---------------------------------------------------------------------------
-- 0. WIPE existing business data (keep flyway_schema_history)
-- ---------------------------------------------------------------------------
TRUNCATE TABLE wallet_transactions;
TRUNCATE TABLE user_referral_codes;
TRUNCATE TABLE zone_surge_rules;
TRUNCATE TABLE subscription_deliveries;
TRUNCATE TABLE support_tickets;
TRUNCATE TABLE settlement_runs;
TRUNCATE TABLE saga_steps;
TRUNCATE TABLE saga_instances;
TRUNCATE TABLE rider_location_updates;
TRUNCATE TABLE rider_earnings;
TRUNCATE TABLE rider_delivery_batch_orders;
TRUNCATE TABLE rider_delivery_batches;
TRUNCATE TABLE restaurant_settlements;
TRUNCATE TABLE restaurant_ratings_summary;
TRUNCATE TABLE restaurant_order_stats;
TRUNCATE TABLE reviews;
TRUNCATE TABLE review_images;
TRUNCATE TABLE promo_banners;
TRUNCATE TABLE promotion_campaigns;
TRUNCATE TABLE order_timeline_events;
TRUNCATE TABLE orders_archive;
TRUNCATE TABLE orders;
TRUNCATE TABLE order_items;
TRUNCATE TABLE order_item_customizations;
TRUNCATE TABLE order_invoices;
TRUNCATE TABLE order_eta_snapshots;
TRUNCATE TABLE order_delivery_proofs;
TRUNCATE TABLE outbox_events;
TRUNCATE TABLE menu_versions;
TRUNCATE TABLE menu_item_tags;
TRUNCATE TABLE menu_item_ratings;
TRUNCATE TABLE menu_item_ingredients;
TRUNCATE TABLE menu_item_images;
TRUNCATE TABLE menu_item_allergens;
TRUNCATE TABLE menu_item_additional_images;
TRUNCATE TABLE inventory_alerts;
TRUNCATE TABLE idempotency_records;
TRUNCATE TABLE group_order_participants;
TRUNCATE TABLE group_order_members;
TRUNCATE TABLE group_orders;
TRUNCATE TABLE gift_orders;
TRUNCATE TABLE gift_cards;
TRUNCATE TABLE fraud_review_queue;
TRUNCATE TABLE fraud_events;
TRUNCATE TABLE favorite_restaurants;
TRUNCATE TABLE experiment_exposures;
TRUNCATE TABLE dynamic_pricing_rules;
TRUNCATE TABLE disputes;
TRUNCATE TABLE device_tokens;
TRUNCATE TABLE dead_letter_events;
TRUNCATE TABLE data_export_requests;
TRUNCATE TABLE delivery_surveys;
TRUNCATE TABLE customization_choices;
TRUNCATE TABLE customization_options;
TRUNCATE TABLE coupon_usages;
TRUNCATE TABLE coupons;
TRUNCATE TABLE consent_records;
TRUNCATE TABLE churn_scores;
TRUNCATE TABLE city_configs;
TRUNCATE TABLE cart_items;
TRUNCATE TABLE cart_item_customizations;
TRUNCATE TABLE carts;
TRUNCATE TABLE campaign_usages;
TRUNCATE TABLE audit_events;
TRUNCATE TABLE api_keys;
TRUNCATE TABLE agent_cod_wallets;
TRUNCATE TABLE agent_shifts;
TRUNCATE TABLE affiliate_referrals;
TRUNCATE TABLE affiliate_codes;
TRUNCATE TABLE restaurant_features;
TRUNCATE TABLE restaurant_food_types;
TRUNCATE TABLE restaurant_gallery_images;
TRUNCATE TABLE restaurant_gallery;
TRUNCATE TABLE restaurant_cuisines;
TRUNCATE TABLE menu_items;
TRUNCATE TABLE menu_categories;
TRUNCATE TABLE customer_notification_preferences;
TRUNCATE TABLE customer_memberships;
TRUNCATE TABLE membership_plans;
TRUNCATE TABLE restaurants;
TRUNCATE TABLE addresses;
TRUNCATE TABLE delivery_zones;
TRUNCATE TABLE cities;
TRUNCATE TABLE delivery_agents;
TRUNCATE TABLE restaurant_owners;
TRUNCATE TABLE customers;
TRUNCATE TABLE users;
TRUNCATE TABLE cuisines;
TRUNCATE TABLE tenants;

SET FOREIGN_KEY_CHECKS = 1;

-- ---------------------------------------------------------------------------
-- 1. TENANTS
-- ---------------------------------------------------------------------------
INSERT INTO tenants (id, name, domain, brand_name, logo_url, theme_color, currency, is_active, created_at) VALUES
(1, 'Bhukkad India', 'bhukkad.com', 'Bhukkad', 'https://cdn.bhukkad.com/logo.png', '#E23744', 'INR', 1, NOW());

-- ---------------------------------------------------------------------------
-- 2. CITIES
-- ---------------------------------------------------------------------------
INSERT INTO cities (id, name, country, currency, timezone, supported_payment_methods, default_min_order_amount, is_serviceable, is_active, created_at) VALUES
(1,  'Bengaluru', 'India', 'INR', 'Asia/Kolkata', 'UPI,CARD,COD,WALLET', 99, 1, 1, NOW()),
(2,  'Mumbai',    'India', 'INR', 'Asia/Kolkata', 'UPI,CARD,COD,WALLET', 99, 1, 1, NOW()),
(3,  'Delhi',     'India', 'INR', 'Asia/Kolkata', 'UPI,CARD,COD,WALLET', 99, 1, 1, NOW()),
(4,  'Hyderabad', 'India', 'INR', 'Asia/Kolkata', 'UPI,CARD,COD,WALLET', 99, 1, 1, NOW()),
(5,  'Chennai',   'India', 'INR', 'Asia/Kolkata', 'UPI,CARD,COD,WALLET', 99, 1, 1, NOW()),
(6,  'Pune',      'India', 'INR', 'Asia/Kolkata', 'UPI,CARD,COD,WALLET', 99, 1, 1, NOW()),
(7,  'Kolkata',   'India', 'INR', 'Asia/Kolkata', 'UPI,CARD,COD,WALLET', 99, 1, 1, NOW()),
(8,  'Jaipur',    'India', 'INR', 'Asia/Kolkata', 'UPI,CARD,COD,WALLET', 99, 1, 1, NOW());

-- ---------------------------------------------------------------------------
-- 3. DELIVERY ZONES (Bengaluru focus + other cities)
-- ---------------------------------------------------------------------------
INSERT INTO delivery_zones (id, name, city, center_latitude, center_longitude, radius_km, base_delivery_fee, per_km_fee, surge_multiplier, is_active, free_delivery_above, created_at) VALUES
(1,  'MG Road',        'Bengaluru', 12.9757,  77.6033,  6, 30, 6, 1, 1, 299, NOW()),
(2,  'Indiranagar',    'Bengaluru', 12.9719,  77.6412,  5, 25, 6, 1, 1, 299, NOW()),
(3,  'Koramangala',    'Bengaluru', 12.9352,  77.6245,  6, 25, 6, 1, 1, 299, NOW()),
(4,  'HSR Layout',     'Bengaluru', 12.9121,  77.6446,  6, 30, 6, 1, 1, 299, NOW()),
(5,  'Whitefield',     'Bengaluru', 12.9698,  77.7500,  8, 40, 7, 1, 1, 399, NOW()),
(6,  'Jayanagar',      'Bengaluru', 12.9308,  77.5838,  6, 25, 6, 1, 1, 299, NOW()),
(7,  'Marathahalli',   'Bengaluru', 12.9569,  77.7011,  6, 35, 6, 1, 1, 299, NOW()),
(8,  'Rajajinagar',    'Bengaluru', 12.9916,  77.5527,  7, 30, 6, 1, 1, 299, NOW()),
(9,  'BTM Layout',     'Bengaluru', 12.9166,  77.6101,  6, 30, 6, 1, 1, 299, NOW()),
(10, 'Andheri',        'Mumbai',    19.1136,  72.8697,  7, 35, 7, 1, 1, 399, NOW()),
(11, 'Bandra',         'Mumbai',    19.0596,  72.8295,  5, 35, 7, 1, 1, 399, NOW()),
(12, 'Connaught Place','Delhi',     28.6315,  77.2167,  6, 30, 6, 1, 1, 299, NOW()),
(13, 'Gachibowli',     'Hyderabad', 17.4401,  78.3489,  7, 30, 6, 1, 1, 299, NOW()),
(14, 'T Nagar',        'Chennai',   13.0418,  80.2341,  6, 30, 6, 1, 1, 299, NOW()),
(15, 'Koregaon Park',  'Pune',      18.5362,  73.8933,  6, 30, 6, 1, 1, 299, NOW());

-- ---------------------------------------------------------------------------
-- 4. CUISINES
-- ---------------------------------------------------------------------------
INSERT INTO cuisines (id, name, active, image_url) VALUES
(1,  'North Indian',  1, 'https://cdn.bhukkad.com/cuisine/north-indian.png'),
(2,  'South Indian',  1, 'https://cdn.bhukkad.com/cuisine/south-indian.png'),
(3,  'Chinese',       1, 'https://cdn.bhukkad.com/cuisine/chinese.png'),
(4,  'Italian',       1, 'https://cdn.bhukkad.com/cuisine/italian.png'),
(5,  'Mughlai',       1, 'https://cdn.bhukkad.com/cuisine/mughlai.png'),
(6,  'Biryani',       1, 'https://cdn.bhukkad.com/cuisine/biryani.png'),
(7,  'Fast Food',     1, 'https://cdn.bhukkad.com/cuisine/fast-food.png'),
(8,  'Street Food',   1, 'https://cdn.bhukkad.com/cuisine/street-food.png'),
(9,  'Desserts',      1, 'https://cdn.bhukkad.com/cuisine/desserts.png'),
(10, 'Beverages',     1, 'https://cdn.bhukkad.com/cuisine/beverages.png'),
(11, 'Seafood',       1, 'https://cdn.bhukkad.com/cuisine/seafood.png'),
(12, 'Healthy',       1, 'https://cdn.bhukkad.com/cuisine/healthy.png'),
(13, 'Punjabi',       1, 'https://cdn.bhukkad.com/cuisine/punjabi.png'),
(14, 'Continental',   1, 'https://cdn.bhukkad.com/cuisine/continental.png'),
(15, 'Kerala',        1, 'https://cdn.bhukkad.com/cuisine/kerala.png'),
(16, 'Bakery',        1, 'https://cdn.bhukkad.com/cuisine/bakery.png'),
(17, 'Kebab',         1, 'https://cdn.bhukkad.com/cuisine/kebab.png');

-- ---------------------------------------------------------------------------
-- 5. USERS
--    BCrypt hashes:
--      admin@bhukkad.dev    -> Admin@123456  => $2a$10$o9a9JYxjW2lLP0NN6JkpGu7QatHDuOJXCK1c5pv65.71It/YqWouq
--      owners / agents      -> Owner@123 / Agent@123 (see below)
--      customers            -> Customer@123 => $2a$10$OMoESGYxTZCFe3nOHA1BO.DPDmLmCEeWlhv6K90SD39kalJRV7d6K
-- ---------------------------------------------------------------------------
INSERT INTO users (id, full_name, email, password, phone_number, role, active, email_verified, created_at, updated_at, profile_image_url) VALUES
-- Admin
(1,  'Vivek Ghosh',        'admin@bhukkad.dev',      '$2a$10$o9a9JYxjW2lLP0NN6JkpGu7QatHDuOJXCK1c5pv65.71It/YqWouq', '9886000001', 'ADMIN',            1, 1, NOW(), NOW(), 'https://i.pravatar.cc/150?u=admin'),
-- Restaurant owners (password: Owner@123 -> $2a$10$jMSl7DQbiORYK9o.I/V8M.p51JNm26527yCZI3awz4ZLdO3e9a66C)
(2,  'Rajesh Kumar',       'rajesh.kumar@spiceroute.in',   '$2a$10$jMSl7DQbiORYK9o.I/V8M.p51JNm26527yCZI3awz4ZLdO3e9a66C', '9886000002', 'RESTAURANT_OWNER', 1, 1, NOW(), NOW(), 'https://i.pravatar.cc/150?u=rajesh'),
(3,  'Priya Sharma',       'priya.sharma@biryani.in',      '$2a$10$jMSl7DQbiORYK9o.I/V8M.p51JNm26527yCZI3awz4ZLdO3e9a66C', '9886000003', 'RESTAURANT_OWNER', 1, 1, NOW(), NOW(), 'https://i.pravatar.cc/150?u=priya'),
(4,  'Arun Iyer',          'arun.iyer@dosaplaza.in',       '$2a$10$jMSl7DQbiORYK9o.I/V8M.p51JNm26527yCZI3awz4ZLdO3e9a66C', '9886000004', 'RESTAURANT_OWNER', 1, 1, NOW(), NOW(), 'https://i.pravatar.cc/150?u=arun'),
(5,  'Ananya Das',         'ananya.das@greenbowl.in',      '$2a$10$jMSl7DQbiORYK9o.I/V8M.p51JNm26527yCZI3awz4ZLdO3e9a66C', '9886000005', 'RESTAURANT_OWNER', 1, 1, NOW(), NOW(), 'https://i.pravatar.cc/150?u=ananya'),
(6,  'Harpreet Singh',     'harpreet@punjabirasoi.in',     '$2a$10$jMSl7DQbiORYK9o.I/V8M.p51JNm26527yCZI3awz4ZLdO3e9a66C', '9886000006', 'RESTAURANT_OWNER', 1, 1, NOW(), NOW(), 'https://i.pravatar.cc/150?u=harpreet'),
(7,  'Meera Nair',         'meera.nair@coastalcatch.in',   '$2a$10$jMSl7DQbiORYK9o.I/V8M.p51JNm26527yCZI3awz4ZLdO3e9a66C', '9886000007', 'RESTAURANT_OWNER', 1, 1, NOW(), NOW(), 'https://i.pravatar.cc/150?u=meera'),
(8,  'Kevin D Souza',      'kevin@wokexpress.in',          '$2a$10$jMSl7DQbiORYK9o.I/V8M.p51JNm26527yCZI3awz4ZLdO3e9a66C', '9886000008', 'RESTAURANT_OWNER', 1, 1, NOW(), NOW(), 'https://i.pravatar.cc/150?u=kevin'),
(9,  'Imran Khan',         'imran@tandoorinights.in',      '$2a$10$jMSl7DQbiORYK9o.I/V8M.p51JNm26527yCZI3awz4ZLdO3e9a66C', '9886000009', 'RESTAURANT_OWNER', 1, 1, NOW(), NOW(), 'https://i.pravatar.cc/150?u=imran'),
(10, 'Ravi Patil',         'ravi@bombaystreet.in',         '$2a$10$jMSl7DQbiORYK9o.I/V8M.p51JNm26527yCZI3awz4ZLdO3e9a66C', '9886000010', 'RESTAURANT_OWNER', 1, 1, NOW(), NOW(), 'https://i.pravatar.cc/150?u=ravi'),
(11, 'Sofia Fernandes',    'sofia@cafemilano.in',          '$2a$10$jMSl7DQbiORYK9o.I/V8M.p51JNm26527yCZI3awz4ZLdO3e9a66C', '9886000011', 'RESTAURANT_OWNER', 1, 1, NOW(), NOW(), 'https://i.pravatar.cc/150?u=sofia'),
-- Delivery agents (password: Agent@123 -> $2a$10$9HRxmh1/SANYxE/7.VRiLuw0jGrj1WKWtygQn1ZQHgghSzy/XH/3q)
(12, 'Suresh Yadav',       'suresh.yadav@bhukkad.delivery', '$2a$10$9HRxmh1/SANYxE/7.VRiLuw0jGrj1WKWtygQn1ZQHgghSzy/XH/3q', '9886000012', 'DELIVERY_AGENT', 1, 1, NOW(), NOW(), 'https://i.pravatar.cc/150?u=suresh'),
(13, 'Mohammed Rafiq',     'rafiq@bhukkad.delivery',        '$2a$10$9HRxmh1/SANYxE/7.VRiLuw0jGrj1WKWtygQn1ZQHgghSzy/XH/3q', '9886000013', 'DELIVERY_AGENT', 1, 1, NOW(), NOW(), 'https://i.pravatar.cc/150?u=rafiq'),
(14, 'Lakshmi Narayan',    'lakshmi@bhukkad.delivery',      '$2a$10$9HRxmh1/SANYxE/7.VRiLuw0jGrj1WKWtygQn1ZQHgghSzy/XH/3q', '9886000014', 'DELIVERY_AGENT', 1, 1, NOW(), NOW(), 'https://i.pravatar.cc/150?u=lakshmi'),
(15, 'Dinesh Reddy',       'dinesh@bhukkad.delivery',       '$2a$10$9HRxmh1/SANYxE/7.VRiLuw0jGrj1WKWtygQn1ZQHgghSzy/XH/3q', '9886000015', 'DELIVERY_AGENT', 1, 1, NOW(), NOW(), 'https://i.pravatar.cc/150?u=dinesh'),
(16, 'Aisha Begum',        'aisha@bhukkad.delivery',        '$2a$10$9HRxmh1/SANYxE/7.VRiLuw0jGrj1WKWtygQn1ZQHgghSzy/XH/3q', '9886000016', 'DELIVERY_AGENT', 1, 1, NOW(), NOW(), 'https://i.pravatar.cc/150?u=aisha'),
-- Customers (password: Customer@123 -> $2a$10$OMoESGYxTZCFe3nOHA1BO.DPDmLmCEeWlhv6K90SD39kalJRV7d6K)
(17, 'Aarav Sharma',       'aarav.sharma@example.com',   '$2a$10$OMoESGYxTZCFe3nOHA1BO.DPDmLmCEeWlhv6K90SD39kalJRV7d6K', '9876500001', 'CUSTOMER', 1, 1, NOW(), NOW(), 'https://i.pravatar.cc/150?u=aarav'),
(18, 'Sneha Patel',        'sneha.patel@example.com',    '$2a$10$OMoESGYxTZCFe3nOHA1BO.DPDmLmCEeWlhv6K90SD39kalJRV7d6K', '9876500002', 'CUSTOMER', 1, 1, NOW(), NOW(), 'https://i.pravatar.cc/150?u=sneha'),
(19, 'Rohan Gupta',        'rohan.gupta@example.com',    '$2a$10$OMoESGYxTZCFe3nOHA1BO.DPDmLmCEeWlhv6K90SD39kalJRV7d6K', '9876500003', 'CUSTOMER', 1, 1, NOW(), NOW(), 'https://i.pravatar.cc/150?u=rohan'),
(20, 'Isha Verma',         'isha.verma@example.com',     '$2a$10$OMoESGYxTZCFe3nOHA1BO.DPDmLmCEeWlhv6K90SD39kalJRV7d6K', '9876500004', 'CUSTOMER', 1, 1, NOW(), NOW(), 'https://i.pravatar.cc/150?u=isha'),
(21, 'Karthik Reddy',      'karthik.reddy@example.com',  '$2a$10$OMoESGYxTZCFe3nOHA1BO.DPDmLmCEeWlhv6K90SD39kalJRV7d6K', '9876500005', 'CUSTOMER', 1, 1, NOW(), NOW(), 'https://i.pravatar.cc/150?u=karthik'),
(22, 'Pooja Malhotra',     'pooja.malhotra@example.com', '$2a$10$OMoESGYxTZCFe3nOHA1BO.DPDmLmCEeWlhv6K90SD39kalJRV7d6K', '9876500006', 'CUSTOMER', 1, 1, NOW(), NOW(), 'https://i.pravatar.cc/150?u=pooja'),
(23, 'Aditya Kulkarni',    'aditya.kulkarni@example.com','$2a$10$OMoESGYxTZCFe3nOHA1BO.DPDmLmCEeWlhv6K90SD39kalJRV7d6K', '9876500007', 'CUSTOMER', 1, 1, NOW(), NOW(), 'https://i.pravatar.cc/150?u=aditya'),
(24, 'Nisha Menon',        'nisha.menon@example.com',    '$2a$10$OMoESGYxTZCFe3nOHA1BO.DPDmLmCEeWlhv6K90SD39kalJRV7d6K', '9876500008', 'CUSTOMER', 1, 1, NOW(), NOW(), 'https://i.pravatar.cc/150?u=nisha'),
(25, 'Vikram Rathore',     'vikram.rathore@example.com', '$2a$10$OMoESGYxTZCFe3nOHA1BO.DPDmLmCEeWlhv6K90SD39kalJRV7d6K', '9876500009', 'CUSTOMER', 1, 1, NOW(), NOW(), 'https://i.pravatar.cc/150?u=vikram'),
(26, 'Tanvi Kulkarni',     'tanvi.kulkarni@example.com', '$2a$10$OMoESGYxTZCFe3nOHA1BO.DPDmLmCEeWlhv6K90SD39kalJRV7d6K', '9876500010', 'CUSTOMER', 1, 1, NOW(), NOW(), 'https://i.pravatar.cc/150?u=tanvi');

-- ---------------------------------------------------------------------------
-- 6. ROLE EXTENSIONS
-- ---------------------------------------------------------------------------
INSERT INTO customers (id, wallet_balance, loyalty_points, referral_code, loyalty_tier) VALUES
(17, 500.00, 120, 'AARAV50', 'GOLD'),
(18, 250.00, 60,  'SNEHA25', 'SILVER'),
(19, 100.00, 30,  'ROHAN10', 'BRONZE'),
(20, 750.00, 200, 'ISHA75',  'PLATINUM'),
(21, 0.00,   10,  'KARTHIK5','BRONZE'),
(22, 300.00, 80,  'POOJA30', 'SILVER'),
(23, 150.00, 45,  'ADITYA15','BRONZE'),
(24, 600.00, 150, 'NISHA60', 'GOLD'),
(25, 20.00,   5,  'VIKRAM2', 'BRONZE'),
(26, 400.00, 95,  'TANVI40', 'SILVER');

INSERT INTO restaurant_owners (id, business_license, verified) VALUES
(2,  'FSSAI-1122334455', 1),
(3,  'FSSAI-1122334456', 1),
(4,  'FSSAI-1122334457', 1),
(5,  'FSSAI-1122334458', 1),
(6,  'FSSAI-1122334459', 1),
(7,  'FSSAI-1122334460', 1),
(8,  'FSSAI-1122334461', 1),
(9,  'FSSAI-1122334462', 1),
(10, 'FSSAI-1122334463', 1),
(11, 'FSSAI-1122334464', 1);

INSERT INTO delivery_agents (id, available, verified, vehicle_type, vehicle_number, license_number, average_rating, total_deliveries, current_latitude, current_longitude) VALUES
(12, 1, 1, 'BICYCLE',  'KA-01-AB-1234', 'DL-KA-2023-1122', 4.8, 1520, 12.9716, 77.5946),
(13, 1, 1, 'MOTORCYCLE','KA-02-CD-5678', 'DL-KA-2022-3344', 4.6, 980,  12.9352, 77.6245),
(14, 1, 1, 'MOTORCYCLE','KA-03-EF-9012', 'DL-KA-2024-5566', 4.9, 2100, 12.9121, 77.6446),
(15, 1, 1, 'SCOOTER',   'KA-04-GH-3456', 'DL-KA-2021-7788', 4.5, 760,  12.9698, 77.7500),
(16, 0, 1, 'MOTORCYCLE','KA-05-IJ-7890', 'DL-KA-2020-9900', 4.7, 1340, 12.9308, 77.5838);

-- ---------------------------------------------------------------------------
-- 7. ADDRESSES (1-10 restaurant, 11-30 customer: 2 each)
-- ---------------------------------------------------------------------------
INSERT INTO addresses (id, address_line1, address_line2, city, state, pincode, latitude, longitude, label, landmark, type, is_default, customer_id) VALUES
-- Restaurant addresses (customer_id = NULL)
(1,  '1 MG Road, Brigade Rd',         NULL, 'Bengaluru', 'Karnataka', '560001', 12.9757, 77.6033, 'Spice Route',      'Near Metro Station',   NULL, 0, NULL),
(2,  '100 Feet Road, Indiranagar',    NULL, 'Bengaluru', 'Karnataka', '560038', 12.9719, 77.6412, 'Biryani Blues',    'Above Big Bazaar',     NULL, 0, NULL),
(3,  '80 Feet Road, Koramangala',     NULL, 'Bengaluru', 'Karnataka', '560095', 12.9352, 77.6245, 'Dosa Plaza',       'Opp. Forum Mall',      NULL, 0, NULL),
(4,  '27th Main, HSR Layout',         NULL, 'Bengaluru', 'Karnataka', '560102', 12.9121, 77.6446, 'Green Bowl',       'Sector 2',             NULL, 0, NULL),
(5,  'ITPL Main Road, Whitefield',    NULL, 'Bengaluru', 'Karnataka', '560066', 12.9698, 77.7500, 'Punjabi Rasoi',    'Near Inorbit Mall',    NULL, 0, NULL),
(6,  '11th Main, Jayanagar 4th Block',NULL, 'Bengaluru', 'Karnataka', '560011', 12.9308, 77.5838, 'Coastal Catch',    'Near Cool Joint',      NULL, 0, NULL),
(7,  'Outer Ring Road, Marathahalli', NULL, 'Bengaluru', 'Karnataka', '560037', 12.9569, 77.7011, 'Wok Express',      'Near Kalamandir',      NULL, 0, NULL),
(8,  'Dr Rajkumar Road, Rajajinagar', NULL, 'Bengaluru', 'Karnataka', '560010', 12.9916, 77.5527, 'Tandoori Nights',  'Near Orion Mall',      NULL, 0, NULL),
(9,  '100 Ft Ring Road, BTM Layout',  NULL, 'Bengaluru', 'Karnataka', '560068', 12.9166, 77.6101, 'Bombay Street',    'Near Udupi Garden',    NULL, 0, NULL),
(10, '12th Main, Indiranagar',        NULL, 'Bengaluru', 'Karnataka', '560008', 12.9784, 77.6408, 'Cafe Milano',      'Near Metropolis',      NULL, 0, NULL),
-- Customer addresses (ids 11-30)
(11, 'Flat 302, Green Meadows', 'Koramangala 5th Block', 'Bengaluru', 'Karnataka', '560095', 12.9342, 77.6147, 'Home',  'Near Sony World', 'HOME', 1, 17),
(12, 'WeWork Prestige Atlanta', '80 Feet Road Koramangala', 'Bengaluru', 'Karnataka', '560095', 12.9352, 77.6245, 'Work',  '4th Floor',       'WORK', 0, 17),
(13, 'B-1201, Sobha Dream Acres', 'Panathur, Outer Ring Road', 'Bengaluru', 'Karnataka', '560103', 12.9280, 77.6948, 'Home', 'Near Panathur Junction', 'HOME', 1, 18),
(14, 'Accenture Campus, Manyata', 'Nagawara', 'Bengaluru', 'Karnataka', '560045', 13.0358, 77.6190, 'Work', 'Block C',         'WORK', 0, 18),
(15, 'C-404, Prestige Lakeside Habitat', 'Varthur Road', 'Bengaluru', 'Karnataka', '560087', 12.9593, 77.6975, 'Home', 'Near Varthur Lake','HOME', 1, 19),
(16, 'Infosys EC, Electronic City', 'Hosur Road', 'Bengaluru', 'Karnataka', '560100', 12.8451, 77.6602, 'Work', 'SEZ Building',    'WORK', 0, 19),
(17, 'A-702, Salarpuria Splendour', 'HSR Layout Sector 1', 'Bengaluru', 'Karnataka', '560102', 12.9166, 77.6412, 'Home', 'Near Agara Lake', 'HOME', 1, 20),
(18, 'Dell Campus, Varthur', 'Outer Ring Road', 'Bengaluru', 'Karnataka', '560103', 12.9442, 77.6990, 'Work', 'Tower D',         'WORK', 0, 20),
(19, 'House No 45, 3rd Cross', 'Jayanagar 4th Block', 'Bengaluru', 'Karnataka', '560011', 12.9240, 77.5838, 'Home', 'Near Jayanagar Metro', 'HOME', 1, 21),
(20, 'Wipro SEZ, Sarjapur Road', 'Doddakannelli', 'Bengaluru', 'Karnataka', '560035', 12.9071, 77.6764, 'Work', 'Building 8',      'WORK', 0, 21),
(21, 'G-1101, Brigade Gateway', 'Rajajinagar, West of Chord Road', 'Bengaluru', 'Karnataka', '560055', 12.9916, 77.5527, 'Home', 'Near Orion Mall', 'HOME', 1, 22),
(22, 'Amazon Office, RMZ Ecoworld', 'Bellandur', 'Bengaluru', 'Karnataka', '560103', 12.9314, 77.6873, 'Work', 'Building 6',      'WORK', 0, 22),
(23, 'Villa 12, Palm Meadows', 'Whitefield Main Road', 'Bengaluru', 'Karnataka', '560066', 12.9698, 77.7500, 'Home', 'Near ITPL',       'HOME', 1, 23),
(24, 'IBM Campus, Manyata', 'Nagawara', 'Bengaluru', 'Karnataka', '560045', 13.0358, 77.6190, 'Work', 'Block B',          'WORK', 0, 23),
(25, 'Flat 8B, Brigade Exotica', 'BTM 2nd Stage', 'Bengaluru', 'Karnataka', '560076', 12.9166, 77.6101, 'Home', 'Near BTM Layout','HOME', 1, 24),
(26, 'Mindtree Campus, Kalinga', 'Marathahalli - Sarjapur Road', 'Bengaluru', 'Karnataka', '560037', 12.9166, 77.6811, 'Work', 'Tower 2',        'WORK', 0, 24),
(27, 'Flat 501, Sri Sai Residency', 'Indiranagar Double Road', 'Bengaluru', 'Karnataka', '560038', 12.9719, 77.6412, 'Home', 'Near 100 Ft Road','HOME', 1, 25),
(28, 'Microsoft Campus, Gachibowli', 'Survey No 14, Manikonda', 'Hyderabad', 'Telangana', '500032', 17.4401, 78.3489, 'Work', 'SEZ',             'WORK', 0, 25),
(29, 'A-1002, Prestige Ozone', 'Devarabisanahalli, Bellandur', 'Bengaluru', 'Karnataka', '560103', 12.9270, 77.6890, 'Home', 'Near Eco Space',  'HOME', 1, 26),
(30, 'JPMorgan, Bagmane Tech Park', 'CV Raman Nagar', 'Bengaluru', 'Karnataka', '560093', 12.9857, 77.6603, 'Work', 'Block C',          'WORK', 0, 26);

-- ---------------------------------------------------------------------------
-- 8. RESTAURANTS
-- ---------------------------------------------------------------------------
INSERT INTO restaurants (id, name, description, owner_id, address_id, tenant_id, opening_time, closing_time,
                         is_active, is_open, is_pure_veg, free_delivery_available, free_delivery_above,
                         minimum_order_amount, delivery_fee, average_rating, total_reviews, average_delivery_time,
                         image_url, fssai_number, commission_percent, busy_mode, onboarding_status, created_at, updated_at) VALUES
(1,  'Spice Route',        'Authentic North Indian & Mughlai delicacies in the heart of the city. Famous for butter chicken and dal makhani.', 2, 1, 1, '11:00:00', '23:30:00', 1, 1, 0, 1, 299, 99,  30, 4.3, 1240, 35, 'https://images.unsplash.com/photo-1585937421612-70a008356fbe', 'FSSAI-1122334455', 15.0, 0, 'APPROVED', NOW(), NOW()),
(2,  'Biryani Blues',      'Hyderabadi dum biryani done right — slow cooked over charcoal. A Bengaluru institution since 1995.', 3, 2, 1, '10:00:00', '23:00:00', 1, 1, 0, 1, 399, 149, 40, 4.5, 3210, 40, 'https://images.unsplash.com/photo-1563379091339-03b21ab4a4f8', 'FSSAI-1122334456', 15.0, 0, 'APPROVED', NOW(), NOW()),
(3,  'Dosa Plaza',         'Crispy golden dosas, fluffy idlis and filter coffee — classic Udupi-style South Indian breakfast and meals.', 4, 3, 1, '07:00:00', '22:30:00', 1, 1, 1, 1, 199, 79,  30, 4.2, 2890, 25, 'https://images.unsplash.com/photo-1630383249896-424e482df921', 'FSSAI-1122334457', 15.0, 0, 'APPROVED', NOW(), NOW()),
(4,  'Green Bowl',         'Fresh salads, grain bowls and cold-pressed juices. Calorie-counted, farm-to-fork healthy food.', 5, 4, 1, '08:00:00', '22:00:00', 1, 1, 1, 1, 299, 99,  45, 4.6, 980,  30, 'https://images.unsplash.com/photo-1512621776951-a57141f2eefd', 'FSSAI-1122334458', 15.0, 0, 'APPROVED', NOW(), NOW()),
(5,  'Punjabi Rasoi',      'Hearty Amritsari food — chole bhature, kulchas and sarson ka saag straight from a Punjabi dhaba.', 6, 5, 1, '08:00:00', '22:30:00', 1, 1, 1, 1, 199, 59,  39, 4.1, 1560, 30, 'https://images.unsplash.com/photo-1601050690597-df0568f70950', 'FSSAI-1122334459', 15.0, 0, 'APPROVED', NOW(), NOW()),
(6,  'Coastal Catch',      'Fresh catch from the Malabar coast — fish curries, appam, prawn ghee roast and Kerala-style meals.', 7, 6, 1, '11:00:00', '23:00:00', 1, 1, 0, 1, 299, 70,  50, 4.4, 870,  35, 'https://images.unsplash.com/photo-1519708227418-c8fd9a32b7a2', 'FSSAI-1122334460', 15.0, 0, 'APPROVED', NOW(), NOW()),
(7,  'Wok Express',        'Wok-tossed Chinese, Thai and Indo-Chinese favourites — noodles, fried rice, manchurian and more.', 8, 7, 1, '11:00:00', '23:30:00', 1, 1, 0, 1, 199, 49,  39, 4.0, 2230, 25, 'https://images.unsplash.com/photo-1563245372-f21724e3856d', 'FSSAI-1122334461', 15.0, 0, 'APPROVED', NOW(), NOW()),
(8,  'Tandoori Nights',    'Charcoal-grilled kebabs, tandoori chicken and rich Mughlai curries in a royal setting.', 9, 8, 1, '12:00:00', '00:00:00', 1, 1, 0, 1, 399, 89,  79, 4.5, 1870, 45, 'https://images.unsplash.com/photo-1599487488170-d11ec9c172f0', 'FSSAI-1122334462', 15.0, 0, 'APPROVED', NOW(), NOW()),
(9,  'Bombay Street Food', 'Mumbai''s legendary street food — vada pav, pav bhaji, pani puri and cutting chai.', 10, 9, 1, '09:00:00', '23:00:00', 1, 1, 1, 1, 99,  29,  19, 4.3, 3420, 20, 'https://images.unsplash.com/photo-1606491956689-2ea866880c84', 'FSSAI-1122334463', 15.0, 0, 'APPROVED', NOW(), NOW()),
(10, 'Cafe Milano',        'Wood-fired pizzas, pasta, risotto and indulgent desserts with a cozy cafe vibe.', 11, 10, 1, '10:00:00', '23:30:00', 1, 1, 0, 1, 249, 59,  49, 4.4, 1290, 30, 'https://images.unsplash.com/photo-1513104890138-7c749659a591', 'FSSAI-1122334464', 15.0, 0, 'APPROVED', NOW(), NOW());

-- ---------------------------------------------------------------------------
-- 9. RESTAURANT-CUISINE MAPPING
-- ---------------------------------------------------------------------------
INSERT INTO restaurant_cuisines (restaurant_id, cuisine_id) VALUES
(1, 1), (1, 5),   -- Spice Route: North Indian, Mughlai
(2, 6), (2, 5),   -- Biryani Blues: Biryani, Mughlai
(3, 2),           -- Dosa Plaza: South Indian
(4, 12), (4, 14), -- Green Bowl: Healthy, Continental
(5, 13), (5, 1),  -- Punjabi Rasoi: Punjabi, North Indian
(6, 11), (6, 15), -- Coastal Catch: Seafood, Kerala
(7, 3),           -- Wok Express: Chinese
(8, 17), (8, 5),  -- Tandoori Nights: Kebab, Mughlai
(9, 8), (9, 7),   -- Bombay Street Food: Street Food, Fast Food
(10, 4), (10, 14), (10, 9); -- Cafe Milano: Italian, Continental, Desserts

-- ---------------------------------------------------------------------------
-- 10. MENU CATEGORIES
-- ---------------------------------------------------------------------------
INSERT INTO menu_categories (id, restaurant_id, name, description, display_order, active) VALUES
-- Spice Route
(1,  1, 'Starters',       'Tandoor & kebab starters',       1, 1),
(2,  1, 'Main Course',    'Signature curries & gravies',    2, 1),
(3,  1, 'Breads',         'Fresh from the tandoor',         3, 1),
(4,  1, 'Rice & Biryani', 'Basmati rice preparations',      4, 1),
(5,  1, 'Desserts',       'Indian sweets',                  5, 1),
(6,  1, 'Beverages',      'Drinks & lassi',                 6, 1),
-- Biryani Blues
(7,  2, 'Biryani',        'Dum-cooked biryanis',            1, 1),
(8,  2, 'Starters',       'Kebabs & appetizers',            2, 1),
(9,  2, 'Accompaniments', 'Salans, raitas & sides',         3, 1),
(10, 2, 'Desserts',       'Sweets to finish',               4, 1),
-- Dosa Plaza
(11, 3, 'Dosas',          'Crispy dosa varieties',          1, 1),
(12, 3, 'Breakfast',      'Idli, vada, upma & more',        2, 1),
(13, 3, 'Meals',          'Full meals & thalis',            3, 1),
(14, 3, 'Beverages',      'Filter coffee & juices',         4, 1),
-- Green Bowl
(15, 4, 'Salads',         'Fresh garden salads',            1, 1),
(16, 4, 'Bowls',          'Protein & grain bowls',          2, 1),
(17, 4, 'Juices & Smoothies', 'Cold-pressed drinks',        3, 1),
-- Punjabi Rasoi
(18, 5, 'Breakfast',      'Bhature, kulcha & chole',        1, 1),
(19, 5, 'Main Course',    'Punjabi curries',                2, 1),
(20, 5, 'Breads',         'Roti, naan & paratha',           3, 1),
-- Coastal Catch
(21, 6, 'Starters',       'Coastal appetizers',             1, 1),
(22, 6, 'Main Course',    'Fish curries & stews',           2, 1),
(23, 6, 'Breads & Rice',  'Appam, neer dosa, rice',         3, 1),
-- Wok Express
(24, 7, 'Noodles & Rice', 'Wok-tossed staples',             1, 1),
(25, 7, 'Starters',       'Manchurian, spring rolls',       2, 1),
(26, 7, 'Soups',          'Hot & sour, manchow',            3, 1),
-- Tandoori Nights
(27, 8, 'Tandoor',        'Charcoal-grilled kebabs',        1, 1),
(28, 8, 'Main Course',    'Royal Mughlai curries',          2, 1),
(29, 8, 'Breads',         'Roomali, naan, roti',            3, 1),
-- Bombay Street Food
(30, 9, 'Street Snacks',  'Iconic Mumbai snacks',           1, 1),
(31, 9, 'Chats',          'Pani puri, sev puri & more',     2, 1),
(32, 9, 'Beverages',      'Chai & coolers',                 3, 1),
-- Cafe Milano
(33, 10, 'Pizzas',        'Wood-fired pizzas',              1, 1),
(34, 10, 'Pasta & Risotto','Italian staples',               2, 1),
(35, 10, 'Desserts',      'Cakes, tiramisu & more',         3, 1),
(36, 10, 'Beverages',     'Coffee & fresh juices',          4, 1);

-- ---------------------------------------------------------------------------
-- 11. MENU ITEMS  (price in INR; food_type: VEG/NON_VEG/VEGAN/EGGETARIAN)
-- ---------------------------------------------------------------------------
INSERT INTO menu_items (id, category_id, name, description, price, original_price, discount_percentage, food_type, is_veg,
                        is_spicy, spice_level, calories, preparation_time, serving_size, image_url, bestseller, recommended,
                        average_rating, total_ratings, available, stock_quantity, created_at, updated_at) VALUES
-- Spice Route (cat 1-6)
(1,  1, 'Paneer Tikka',            'Char-grilled cottage cheese marinated in yogurt & spices', 289, 329, 12, 'VEG', 1, 0, 'MEDIUM', 340, 20, '8 pcs', 'https://images.unsplash.com/photo-1567188040759-fb8a883dc6d8', 1, 1, 4.4, 320, 1, 100, NOW(), NOW()),
(2,  1, 'Chicken Malai Tikka',     'Creamy, mildly spiced grilled chicken skewers',           329, 359, 8, 'NON_VEG', 0, 0, 'MILD', 380, 22, '8 pcs', 'https://images.unsplash.com/photo-1603894584373-5ac82b2ae398', 0, 1, 4.3, 210, 1, 80, NOW(), NOW()),
(3,  2, 'Butter Chicken',          'Signature tomato-butter gravy with tandoori chicken',      429, 479, 10, 'NON_VEG', 0, 0, 'MEDIUM', 520, 25, '1 serving', 'https://images.unsplash.com/photo-1603894584373-5ac82b2ae398', 1, 1, 4.6, 890, 1, 150, NOW(), NOW()),
(4,  2, 'Dal Makhani',             'Slow-cooked black lentils in butter & cream',              269, NULL, NULL, 'VEG', 1, 0, 'MILD', 300, 25, '1 serving', 'https://images.unsplash.com/photo-1546833999-b9f581a1996d', 1, 0, 4.5, 760, 1, 200, NOW(), NOW()),
(5,  2, 'Paneer Butter Masala',    'Cottage cheese in rich makhani gravy',                     319, NULL, NULL, 'VEG', 1, 0, 'MEDIUM', 410, 22, '1 serving', 'https://images.unsplash.com/photo-1631452180519-c014fe946bc7', 0, 1, 4.3, 540, 1, 120, NOW(), NOW()),
(6,  3, 'Garlic Naan',             'Tandoor-baked naan brushed with garlic butter',            59, NULL, NULL, 'VEG', 1, 0, 'MILD', 180, 10, '1 pc', 'https://images.unsplash.com/photo-1601050690597-df0568f70950', 1, 0, 4.4, 980, 1, 500, NOW(), NOW()),
(7,  3, 'Butter Roti',             'Whole wheat flatbread with butter',                         45, NULL, NULL, 'VEG', 1, 0, 'MILD', 140, 8,  '1 pc', 'https://images.unsplash.com/photo-1601050690597-df0568f70950', 0, 0, 4.2, 610, 1, 600, NOW(), NOW()),
(8,  4, 'Jeera Rice',              'Basmati rice tempered with cumin',                         189, NULL, NULL, 'VEG', 1, 0, 'MILD', 280, 15, '1 serving', 'https://images.unsplash.com/photo-1516684732162-798a0062be99', 0, 0, 4.1, 400, 1, 300, NOW(), NOW()),
(9,  5, 'Gulab Jamun',             'Warm milk-solid dumplings in rose syrup',                  99, NULL, NULL, 'VEG', 1, 0, 'MILD', 250, 5,  '2 pcs', 'https://images.unsplash.com/photo-1567188040759-fb8a883dc6d8', 1, 0, 4.3, 520, 1, 400, NOW(), NOW()),
(10, 6, 'Sweet Lassi',             'Thick, creamy yogurt drink',                               79, NULL, NULL, 'VEG', 1, 0, 'MILD', 180, 5,  '300 ml', 'https://images.unsplash.com/photo-1571006682889-53d3160c5b4a', 1, 0, 4.2, 380, 1, 300, NOW(), NOW()),
-- Biryani Blues (cat 7-10)
(11, 7, 'Hyderabadi Chicken Biryani','Dum biryani with saffron rice & marinated chicken',       349, 399, 12, 'NON_VEG', 0, 1, 'HOT', 650, 30, '1 plate', 'https://images.unsplash.com/photo-1563379091339-03b21ab4a4f8', 1, 1, 4.6, 1200, 1, 200, NOW(), NOW()),
(12, 7, 'Veg Biryani',             'Fragrant rice with seasonal vegetables',                   279, NULL, NULL, 'VEG', 1, 0, 'MEDIUM', 550, 28, '1 plate', 'https://images.unsplash.com/photo-1589302168068-964664d93dc0', 1, 1, 4.4, 680, 1, 250, NOW(), NOW()),
(13, 7, 'Mutton Biryani',          'Slow-cooked mutton dum biryani',                           449, NULL, NULL, 'NON_VEG', 0, 1, 'HOT', 700, 40, '1 plate', 'https://images.unsplash.com/photo-1563379091339-03b21ab4a4f8', 0, 0, 4.5, 540, 1, 120, NOW(), NOW()),
(14, 8, 'Chicken 65',              'Fiery deep-fried chicken appetizer',                       249, 279, 10, 'NON_VEG', 0, 1, 'HOT', 420, 18, '1 plate', 'https://images.unsplash.com/photo-1603894584373-5ac82b2ae398', 1, 0, 4.3, 720, 1, 150, NOW(), NOW()),
(15, 9, 'Mirchi Ka Salan',         'Spicy peanut-coconut curry',                                79, NULL, NULL, 'VEG', 1, 1, 'HOT', 120, 10, '1 bowl', 'https://images.unsplash.com/photo-1546833999-b9f581a1996d', 0, 1, 4.1, 210, 1, 350, NOW(), NOW()),
(16, 9, 'Burani Raita',            'Cooling yogurt with roasted spices',                        69, NULL, NULL, 'VEG', 1, 0, 'MILD', 90, 5,  '1 bowl', 'https://images.unsplash.com/photo-1571006682889-53d3160c5b4a', 0, 0, 4.2, 180, 1, 400, NOW(), NOW()),
(17, 10, 'Double Ka Meetha',       'Hyderabadi bread pudding with saffron',                    89, NULL, NULL, 'VEG', 1, 0, 'MILD', 300, 10, '1 serving', 'https://images.unsplash.com/photo-1567188040759-fb8a883dc6d8', 1, 0, 4.3, 340, 1, 250, NOW(), NOW()),
-- Dosa Plaza (cat 11-14)
(18, 11, 'Masala Dosa',            'Crispy dosa with spiced potato filling, chutney & sambar', 129, 149, 13, 'VEG', 1, 0, 'MILD', 320, 15, '1 pc', 'https://images.unsplash.com/photo-1630383249896-424e482df921', 1, 1, 4.5, 1500, 1, 500, NOW(), NOW()),
(19, 11, 'Mysore Masala Dosa',     'Spicy red chutney spread dosa',                            149, NULL, NULL, 'VEG', 1, 1, 'MEDIUM', 340, 15, '1 pc', 'https://images.unsplash.com/photo-1630383249896-424e482df921', 0, 1, 4.4, 620, 1, 400, NOW(), NOW()),
(20, 11, 'Ghee Roast Dosa',        'Paper-thin dosa roasted in ghee',                           159, NULL, NULL, 'VEG', 1, 0, 'MILD', 360, 18, '1 pc', 'https://images.unsplash.com/photo-1630383249896-424e482df921', 0, 0, 4.3, 480, 1, 300, NOW(), NOW()),
(21, 12, 'Rava Idli',              'Soft semolina idlis with chutney',                         89, NULL, NULL, 'VEG', 1, 0, 'MILD', 180, 10, '2 pcs', 'https://images.unsplash.com/photo-1630383249896-424e482df921', 1, 0, 4.2, 700, 1, 600, NOW(), NOW()),
(22, 12, 'Medu Vada',              'Crispy lentil doughnuts with sambar',                      79, NULL, NULL, 'VEG', 1, 0, 'MILD', 220, 12, '2 pcs', 'https://images.unsplash.com/photo-1630383249896-424e482df921', 0, 0, 4.2, 450, 1, 500, NOW(), NOW()),
(23, 13, 'South Indian Thali',     'Full meal: rice, sambar, rasam, curries, curd & sweet',    249, NULL, NULL, 'VEG', 1, 0, 'MEDIUM', 700, 20, '1 thali', 'https://images.unsplash.com/photo-1630383249896-424e482df921', 1, 1, 4.4, 380, 1, 200, NOW(), NOW()),
(24, 14, 'Filter Coffee',          'Frothy traditional South Indian filter coffee',            49, NULL, NULL, 'VEG', 1, 0, 'MILD', 60, 5,  '180 ml', 'https://images.unsplash.com/photo-1571006682889-53d3160c5b4a', 1, 0, 4.6, 1200, 1, 800, NOW(), NOW()),
-- Green Bowl (cat 15-17)
(25, 15, 'Quinoa Power Bowl',      'Quinoa, roasted veggies, chickpeas & tahini dressing',     349, NULL, NULL, 'VEGAN', 1, 0, 'MILD', 480, 15, '1 bowl', 'https://images.unsplash.com/photo-1512621776951-a57141f2eefd', 1, 1, 4.5, 240, 1, 120, NOW(), NOW()),
(26, 15, 'Grilled Chicken Salad',  'Chicken breast, greens, cherry tomatoes & balsamic',       399, 449, 11, 'NON_VEG', 0, 0, 'MILD', 420, 15, '1 bowl', 'https://images.unsplash.com/photo-1546793665-c74683f339c1', 1, 1, 4.4, 310, 1, 100, NOW(), NOW()),
(27, 16, 'Avocado Toast',          'Sourdough with smashed avocado, chili flakes & lime',      329, NULL, NULL, 'VEGAN', 1, 0, 'MILD', 350, 10, '2 slices', 'https://images.unsplash.com/photo-1541519227354-08fa5d50c44d', 0, 0, 4.3, 180, 1, 90, NOW(), NOW()),
(28, 17, 'Detox Green Juice',      'Kale, spinach, apple, cucumber & ginger',                  179, NULL, NULL, 'VEGAN', 1, 0, 'MILD', 120, 5,  '350 ml', 'https://images.unsplash.com/photo-1600271886742-f049cd451bba', 1, 0, 4.2, 150, 1, 200, NOW(), NOW()),
(29, 17, 'Berry Smoothie Bowl',    'Mixed berries, banana & granola',                          299, NULL, NULL, 'VEGAN', 1, 0, 'MILD', 320, 8,  '1 bowl', 'https://images.unsplash.com/photo-1512621776951-a57141f2eefd', 0, 0, 4.5, 210, 1, 80, NOW(), NOW()),
-- Punjabi Rasoi (cat 18-20)
(30, 18, 'Chole Bhature',          'Punjabi chickpea curry with fluffy bhature',               149, NULL, NULL, 'VEG', 1, 0, 'MEDIUM', 450, 15, '2 pcs', 'https://images.unsplash.com/photo-1606491956689-2ea866880c84', 1, 1, 4.5, 890, 1, 400, NOW(), NOW()),
(31, 18, 'Amritsari Kulcha',       'Stuffed kulcha with chole & pickled onions',               139, NULL, NULL, 'VEG', 1, 0, 'MEDIUM', 380, 15, '2 pcs', 'https://images.unsplash.com/photo-1606491956689-2ea866880c84', 1, 1, 4.4, 720, 1, 350, NOW(), NOW()),
(32, 19, 'Sarson Ka Saag',         'Mustard greens cooked Punjabi style with makki roti',      229, NULL, NULL, 'VEG', 1, 0, 'MEDIUM', 350, 20, '1 serving', 'https://images.unsplash.com/photo-1546833999-b9f581a1996d', 0, 1, 4.3, 560, 1, 150, NOW(), NOW()),
(33, 19, 'Paneer Bhurji',          'Scrambled cottage cheese with peppers & onions',           279, NULL, NULL, 'VEG', 1, 0, 'MEDIUM', 380, 15, '1 serving', 'https://images.unsplash.com/photo-1631452180519-c014fe946bc7', 0, 0, 4.2, 430, 1, 180, NOW(), NOW()),
(34, 20, 'Makki Ki Roti',          'Corn flour flatbread',                                     49, NULL, NULL, 'VEG', 1, 0, 'MILD', 150, 10, '2 pcs', 'https://images.unsplash.com/photo-1601050690597-df0568f70950', 0, 0, 4.2, 280, 1, 400, NOW(), NOW()),
(35, 20, 'Tandoori Roti',          'Whole wheat tandoor bread',                                 39, NULL, NULL, 'VEG', 1, 0, 'MILD', 120, 8,  '2 pcs', 'https://images.unsplash.com/photo-1601050690597-df0568f70950', 0, 0, 4.1, 200, 1, 500, NOW(), NOW()),
-- Coastal Catch (cat 21-23)
(36, 21, 'Prawn Ghee Roast',       'Mangalorean-style prawns roasted in ghee & spices',       429, 469, 8, 'NON_VEG', 0, 1, 'HOT', 420, 20, '1 plate', 'https://images.unsplash.com/photo-1519708227418-c8fd9a32b7a2', 1, 1, 4.6, 460, 1, 100, NOW(), NOW()),
(37, 21, 'Squid Fry',              'Crispy Kerala-style squid rings',                          389, NULL, NULL, 'NON_VEG', 0, 1, 'HOT', 380, 20, '1 plate', 'https://images.unsplash.com/photo-1519708227418-c8fd9a32b7a2', 0, 0, 4.3, 280, 1, 80, NOW(), NOW()),
(38, 22, 'Malabar Fish Curry',     'Fish in tangy coconut curry with raw mango',              379, 429, 11, 'NON_VEG', 0, 1, 'MEDIUM', 460, 25, '1 serving', 'https://images.unsplash.com/photo-1519708227418-c8fd9a32b7a2', 1, 1, 4.5, 520, 1, 120, NOW(), NOW()),
(39, 22, 'Chicken Stew',           'Kerala-style coconut milk chicken stew',                  329, NULL, NULL, 'NON_VEG', 0, 0, 'MILD', 480, 25, '1 serving', 'https://images.unsplash.com/photo-1547592166-23ac45744acd', 0, 1, 4.3, 340, 1, 100, NOW(), NOW()),
(40, 23, 'Appam',                  'Lacy fermented rice pancakes',                             99, NULL, NULL, 'VEG', 1, 0, 'MILD', 180, 15, '3 pcs', 'https://images.unsplash.com/photo-1519708227418-c8fd9a32b7a2', 1, 0, 4.4, 480, 1, 300, NOW(), NOW()),
(41, 23, 'Neer Dosa',              'Soft, thin Mangalorean rice dosa',                         89, NULL, NULL, 'VEG', 1, 0, 'MILD', 160, 12, '4 pcs', 'https://images.unsplash.com/photo-1519708227418-c8fd9a32b7a2', 0, 0, 4.3, 350, 1, 250, NOW(), NOW()),
(42, 23, 'Malabar Parotta',        'Flaky layered Kerala parotta',                             49, NULL, NULL, 'VEG', 1, 0, 'MILD', 220, 10, '1 pc', 'https://images.unsplash.com/photo-1519708227418-c8fd9a32b7a2', 0, 0, 4.2, 400, 1, 500, NOW(), NOW()),
-- Wok Express (cat 24-26)
(43, 24, 'Veg Hakka Noodles',      'Wok-tossed noodles with crunchy vegetables',              199, NULL, NULL, 'EGGETARIAN', 1, 0, 'MEDIUM', 380, 15, '1 serving', 'https://images.unsplash.com/photo-1563245372-f21724e3856d', 1, 0, 4.1, 560, 1, 300, NOW(), NOW()),
(44, 24, 'Chicken Fried Rice',     'Wok-fired rice with chicken & vegetables',                259, NULL, NULL, 'NON_VEG', 0, 0, 'MEDIUM', 420, 15, '1 serving', 'https://images.unsplash.com/photo-1563245372-f21724e3856d', 1, 1, 4.2, 480, 1, 250, NOW(), NOW()),
(45, 24, 'Singapore Noodles',      'Curried rice noodles with prawns & veggies',              299, NULL, NULL, 'NON_VEG', 0, 1, 'HOT', 440, 18, '1 serving', 'https://images.unsplash.com/photo-1563245372-f21724e3856d', 0, 0, 4.1, 360, 1, 150, NOW(), NOW()),
(46, 25, 'Veg Manchurian',         'Crispy veg balls in spicy garlic sauce',                  229, 259, 11, 'VEG', 1, 1, 'MEDIUM', 360, 18, '1 plate', 'https://images.unsplash.com/photo-1563245372-f21724e3856d', 1, 1, 4.2, 420, 1, 200, NOW(), NOW()),
(47, 25, 'Chilli Chicken',         'Indo-Chinese classic in fiery sauce',                     279, NULL, NULL, 'NON_VEG', 0, 1, 'HOT', 380, 18, '1 plate', 'https://images.unsplash.com/photo-1563245372-f21724e3856d', 1, 1, 4.3, 510, 1, 220, NOW(), NOW()),
(48, 25, 'Veg Spring Rolls',       'Crispy rolls with vegetable filling',                     149, NULL, NULL, 'VEG', 1, 0, 'MILD', 260, 12, '4 pcs', 'https://images.unsplash.com/photo-1563245372-f21724e3856d', 0, 0, 4.1, 300, 1, 280, NOW(), NOW()),
(49, 26, 'Manchow Soup',           'Hot & spicy veg soup with crispy noodles',                129, NULL, NULL, 'VEG', 1, 1, 'MEDIUM', 140, 10, '1 bowl', 'https://images.unsplash.com/photo-1563245372-f21724e3856d', 1, 0, 4.2, 220, 1, 300, NOW(), NOW()),
(50, 26, 'Hot & Sour Soup',        'Classic Indo-Chinese soup',                                139, NULL, NULL, 'VEG', 1, 1, 'HOT', 150, 10, '1 bowl', 'https://images.unsplash.com/photo-1563245372-f21724e3856d', 0, 0, 4.0, 180, 1, 250, NOW(), NOW()),
-- Tandoori Nights (cat 27-29)
(51, 27, 'Tandoori Chicken',       'Whole leg marinated in yogurt & spices, charcoal grilled', 449, 499, 10, 'NON_VEG', 0, 1, 'HOT', 520, 30, 'Half', 'https://images.unsplash.com/photo-1599487488170-d11ec9c172f0', 1, 1, 4.6, 890, 1, 150, NOW(), NOW()),
(52, 27, 'Chicken Tikka',          'Boneless tandoori chicken cubes',                          379, NULL, NULL, 'NON_VEG', 0, 0, 'MEDIUM', 380, 25, '8 pcs', 'https://images.unsplash.com/photo-1599487488170-d11ec9c172f0', 1, 0, 4.5, 620, 1, 180, NOW(), NOW()),
(53, 27, 'Sheekh Kebab',           'Minced lamb kebabs on skewers',                            399, NULL, NULL, 'NON_VEG', 0, 1, 'HOT', 460, 28, '6 pcs', 'https://images.unsplash.com/photo-1599487488170-d11ec9c172f0', 0, 1, 4.4, 410, 1, 120, NOW(), NOW()),
(54, 28, 'Mutton Rogan Josh',      'Kashmiri-style slow-cooked mutton curry',                  469, NULL, NULL, 'NON_VEG', 0, 1, 'HOT', 580, 35, '1 serving', 'https://images.unsplash.com/photo-1599487488170-d11ec9c172f0', 1, 0, 4.5, 480, 1, 100, NOW(), NOW()),
(55, 28, 'Chicken Korma',          'Rich, creamy Mughlai chicken curry',                       399, NULL, NULL, 'NON_VEG', 0, 0, 'MILD', 520, 30, '1 serving', 'https://images.unsplash.com/photo-1599487488170-d11ec9c172f0', 0, 1, 4.3, 380, 1, 130, NOW(), NOW()),
(56, 29, 'Roomali Roti',           'Paper-thin hand-stretched bread',                          49, NULL, NULL, 'VEG', 1, 0, 'MILD', 120, 5,  '1 pc', 'https://images.unsplash.com/photo-1599487488170-d11ec9c172f0', 0, 0, 4.2, 150, 1, 600, NOW(), NOW()),
(57, 29, 'Butter Naan',            'Soft naan with butter glaze',                              59, NULL, NULL, 'VEG', 1, 0, 'MILD', 170, 8,  '1 pc', 'https://images.unsplash.com/photo-1599487488170-d11ec9c172f0', 0, 0, 4.3, 200, 1, 500, NOW(), NOW()),
-- Bombay Street Food (cat 30-32)
(58, 30, 'Vada Pav',               'Mumbai icon: spiced potato fritter in pav with chutneys', 49, NULL, NULL, 'VEG', 1, 0, 'MEDIUM', 220, 5,  '1 pc', 'https://images.unsplash.com/photo-1606491956689-2ea866880c84', 1, 1, 4.5, 1500, 1, 800, NOW(), NOW()),
(59, 30, 'Pav Bhaji',              'Buttery mashed veggies with toasted pav',                  129, 149, 13, 'VEG', 1, 0, 'MEDIUM', 420, 10, '2 pav', 'https://images.unsplash.com/photo-1606491956689-2ea866880c84', 1, 1, 4.5, 1100, 1, 600, NOW(), NOW()),
(60, 30, 'Frankie Roll',           'Egg & veg frankie wrap with spicy chutney',                99, NULL, NULL, 'EGGETARIAN', 1, 0, 'MEDIUM', 280, 8,  '1 roll', 'https://images.unsplash.com/photo-1606491956689-2ea866880c84', 0, 1, 4.3, 520, 1, 400, NOW(), NOW()),
(61, 31, 'Pani Puri',              'Crispy puris with tangy spiced water & potato filling',   69, NULL, NULL, 'VEG', 1, 1, 'HOT', 180, 5,  '6 pcs', 'https://images.unsplash.com/photo-1606491956689-2ea866880c84', 1, 0, 4.4, 890, 1, 700, NOW(), NOW()),
(62, 31, 'Sev Puri',               'Flat puris topped with potato, onion & sev',              79, NULL, NULL, 'VEG', 1, 0, 'MEDIUM', 220, 5,  '6 pcs', 'https://images.unsplash.com/photo-1606491956689-2ea866880c84', 0, 0, 4.3, 640, 1, 650, NOW(), NOW()),
(63, 32, 'Mumbai Cutting Chai',    'Strong masala chai served in small glass',                29, NULL, NULL, 'VEG', 1, 0, 'MEDIUM', 50, 3,  '90 ml', 'https://images.unsplash.com/photo-1571006682889-53d3160c5b4a', 1, 0, 4.4, 1200, 1, 1000, NOW(), NOW()),
(64, 32, 'Kokum Cooler',           'Refreshing tangy kokum drink',                             69, NULL, NULL, 'VEGAN', 1, 0, 'MILD', 90, 5,  '300 ml', 'https://images.unsplash.com/photo-1600271886742-f049cd451bba', 0, 0, 4.1, 260, 1, 350, NOW(), NOW()),
-- Cafe Milano (cat 33-36)
(65, 33, 'Margherita Pizza',       'Wood-fired pizza: san marzano tomato, fior di latte, basil', 299, 329, 9, 'VEG', 1, 0, 'MILD', 750, 20, '9 inch', 'https://images.unsplash.com/photo-1513104890138-7c749659a591', 1, 1, 4.5, 820, 1, 200, NOW(), NOW()),
(66, 33, 'Farmhouse Pizza',        'Loaded with peppers, mushroom, onion & olives',            399, NULL, NULL, 'VEG', 1, 0, 'MEDIUM', 820, 22, '9 inch', 'https://images.unsplash.com/photo-1513104890138-7c749659a591', 1, 0, 4.4, 560, 1, 180, NOW(), NOW()),
(67, 33, 'Pepperoni Pizza',        'Spicy salami with mozzarella',                             449, NULL, NULL, 'NON_VEG', 0, 1, 'MEDIUM', 880, 22, '9 inch', 'https://images.unsplash.com/photo-1513104890138-7c749659a591', 0, 1, 4.6, 430, 1, 160, NOW(), NOW()),
(68, 34, 'Spaghetti Aglio Olio',   'Garlic, olive oil, chili & parsley',                       349, NULL, NULL, 'VEGAN', 1, 1, 'MEDIUM', 560, 18, '1 serving', 'https://images.unsplash.com/photo-1551183053-bf91a1d81141', 0, 1, 4.3, 380, 1, 120, NOW(), NOW()),
(69, 34, 'Veg Lasagna',            'Layered pasta with béchamel & marinara',                   379, NULL, NULL, 'EGGETARIAN', 1, 0, 'MILD', 720, 25, '1 serving', 'https://images.unsplash.com/photo-1551183053-bf91a1d81141', 1, 0, 4.4, 460, 1, 140, NOW(), NOW()),
(70, 34, 'Mushroom Risotto',       'Creamy arborio rice with wild mushrooms',                  429, NULL, NULL, 'VEG', 1, 0, 'MILD', 680, 25, '1 serving', 'https://images.unsplash.com/photo-1551183053-bf91a1d81141', 0, 0, 4.5, 340, 1, 100, NOW(), NOW()),
(71, 35, 'Tiramisu',               'Classic Italian coffee dessert',                           249, NULL, NULL, 'VEG', 1, 0, 'MILD', 420, 5,  '1 slice', 'https://images.unsplash.com/photo-1571877227200-a0d98ea607e9', 1, 1, 4.6, 520, 1, 150, NOW(), NOW()),
(72, 35, 'Chocolate Lava Cake',    'Molten center chocolate cake with ice cream',             279, NULL, NULL, 'VEG', 1, 0, 'MILD', 540, 12, '1 pc', 'https://images.unsplash.com/photo-1571877227200-a0d98ea607e9', 1, 0, 4.5, 480, 1, 130, NOW(), NOW()),
(73, 36, 'Cappuccino',             'Espresso with steamed milk foam',                          149, NULL, NULL, 'VEG', 1, 0, 'MILD', 120, 5,  '200 ml', 'https://images.unsplash.com/photo-1571006682889-53d3160c5b4a', 1, 0, 4.5, 800, 1, 600, NOW(), NOW()),
(74, 36, 'Fresh Orange Juice',     'Cold-pressed oranges',                                     129, NULL, NULL, 'VEGAN', 1, 0, 'MILD', 110, 5,  '300 ml', 'https://images.unsplash.com/photo-1600271886742-f049cd451bba', 0, 0, 4.2, 380, 1, 350, NOW(), NOW());

-- ---------------------------------------------------------------------------
-- 12. CUSTOMIZATIONS (a few high-traffic items)
-- ---------------------------------------------------------------------------
INSERT INTO customization_options (id, menu_item_id, name, required, multiple_selection, min_selection, max_selection) VALUES
(1, 1,  'Spice Level', 0, 0, 0, 1),   -- Paneer Tikka
(2, 1,  'Add Ons',     0, 1, 0, 2),
(3, 3,  'Portion',     0, 0, 0, 1),   -- Butter Chicken
(4, 11, 'Spice Level', 0, 0, 0, 1),   -- Hyderabadi Biryani
(5, 11, 'Add Ons',     0, 1, 0, 2),
(6, 18, 'Chutney',     0, 0, 0, 1),   -- Masala Dosa
(7, 65, 'Crust',       0, 0, 0, 1),   -- Margherita Pizza
(8, 65, 'Toppings',    0, 1, 0, 3),
(9, 58, 'Extras',      0, 0, 0, 1);   -- Vada Pav

INSERT INTO customization_choices (id, customization_option_id, name, additional_price, available) VALUES
(1,  1, 'Mild',        0.00, 1),
(2,  1, 'Medium',      0.00, 1),
(3,  1, 'Hot',         0.00, 1),
(4,  2, 'Extra Cheese',49.00, 1),
(5,  2, 'Extra Chutney',19.00, 1),
(6,  2, 'Extra Salad', 29.00, 1),
(7,  3, 'Half',        0.00, 1),
(8,  3, 'Full',        120.00, 1),
(9,  4, 'Mild',        0.00, 1),
(10, 4, 'Medium',      0.00, 1),
(11, 4, 'Extra Spicy', 0.00, 1),
(12, 5, 'Extra Raita', 39.00, 1),
(13, 5, 'Extra Mirchi Salan', 49.00, 1),
(14, 5, 'Boiled Egg',  29.00, 1),
(15, 6, 'Extra Sambar',0.00, 1),
(16, 6, 'Coconut Chutney Only', 0.00, 1),
(17, 7, 'Thin Crust',  0.00, 1),
(18, 7, 'Pan Crust',   49.00, 1),
(19, 7, 'Cheese Burst',99.00, 1),
(20, 8, 'Extra Cheese',79.00, 1),
(21, 8, 'Jalapenos',   39.00, 1),
(22, 8, 'Mushrooms',   49.00, 1),
(23, 8, 'Pepperoni',   89.00, 1),
(24, 9, 'Cheese Slice',25.00, 1),
(25, 9, 'Extra Pav',   15.00, 1);

-- ---------------------------------------------------------------------------
-- 13. MEMBERSHIP PLANS
-- ---------------------------------------------------------------------------
INSERT INTO membership_plans (id, name, description, price_per_month, free_delivery, discount_percent, max_discount_percent, referral_bonus_percent, referral_max_per_month, tier_level, is_active, created_at) VALUES
(1, 'Bhukkad Basic',    'Standard membership with zero delivery fees above threshold', 0.00,   1, 0,  0,  0, 0, 0, 1, NOW()),
(2, 'Bhukkad Gold',     'Free delivery, 5% off every order up to Rs 75, priority support', 99.00, 1, 5, 75, 5, 5, 1, 1, NOW()),
(3, 'Bhukkad Platinum', 'Free delivery, 10% off every order up to Rs 150, priority support', 199.00, 1, 10, 150, 10, 10, 2, 1, NOW());

-- ---------------------------------------------------------------------------
-- 14. COUPONS
-- ---------------------------------------------------------------------------
INSERT INTO coupons (id, code, description, discount_type, discount_value, maximum_discount_amount, minimum_order_amount,
                     per_user_limit, usage_limit, used_count, valid_from, valid_until, active, restaurant_id) VALUES
(1, 'WELCOME50',  '50% off up to Rs 100 on your first order',             'PERCENTAGE',  50,  100, 299,  1, 100000, 0, NOW(), DATE_ADD(NOW(), INTERVAL 1 YEAR), 1, NULL),
(2, 'SAVE10',     'Flat 10% off up to Rs 75',                             'PERCENTAGE',  10,  75,  199,  3, 50000,  0, NOW(), DATE_ADD(NOW(), INTERVAL 6 MONTH), 1, NULL),
(3, 'FREEDEL50',  'Rs 50 off on orders above Rs 499',                     'FIXED_AMOUNT',50,  NULL, 499,  2, 20000,  0, NOW(), DATE_ADD(NOW(), INTERVAL 3 MONTH), 1, NULL),
(4, 'BIRYANI20',  '20% off up to Rs 100 on Biryani Blues',                'PERCENTAGE',  20,  100, 349,  1, 10000,  0, NOW(), DATE_ADD(NOW(), INTERVAL 2 MONTH), 1, 2),
(5, 'NONVEG15',   '15% off up to Rs 80 on non-veg orders',                'PERCENTAGE',  15,  80,  299,  2, 15000,  0, NOW(), DATE_ADD(NOW(), INTERVAL 2 MONTH), 1, NULL),
(6, 'PIZZA25',    '25% off up to Rs 125 at Cafe Milano',                  'PERCENTAGE',  25,  125, 399,  1, 5000,   0, NOW(), DATE_ADD(NOW(), INTERVAL 1 MONTH), 1, 10),
(7, 'DOSA15',     '15% off up to Rs 50 at Dosa Plaza',                    'PERCENTAGE',  15,  50,  149,  1, 5000,   0, NOW(), DATE_ADD(NOW(), INTERVAL 1 MONTH), 1, 3),
(8, 'STREET30',   'Rs 30 off on street food orders above Rs 150',         'FIXED_AMOUNT',30,  NULL, 150,  2, 20000,  0, NOW(), DATE_ADD(NOW(), INTERVAL 3 MONTH), 1, 9);

-- ---------------------------------------------------------------------------
-- 15. CUSTOMER NOTIFICATION PREFERENCES (all channels on)
-- ---------------------------------------------------------------------------
INSERT INTO customer_notification_preferences (customer_id, email_enabled, sms_enabled, whatsapp_enabled, push_enabled, order_updates_enabled, promotions_enabled)
SELECT id, 1, 1, 1, 1, 1, 1 FROM customers;

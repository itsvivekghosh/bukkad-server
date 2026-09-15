#!/usr/bin/env python3
"""
Seed all service databases with sample data matching actual PostgreSQL schemas.
"""

import subprocess
import sys
import bcrypt

DB_HOST = "localhost"
DB_PORT = "5432"
DB_USER = "app"
DB_PASS = "app_pass"

# Generate BCrypt hashes for seed passwords
def hash_password(password):
    return bcrypt.hashpw(password.encode('utf-8'), bcrypt.gensalt(rounds=10)).decode('utf-8')

PASSWORD_HASH = hash_password("test123")

SEEDS = {
    "identity": f"""
-- Customers (insert first so IDs start at 1)
INSERT INTO customers (email, phone_number, full_name, password_hash, is_active, email_verified, created_at, updated_at, wallet_balance, loyalty_points) VALUES
('customer1@test.com', '9835232368', 'Test Customer 1', '{PASSWORD_HASH}', true, true, NOW(), NOW(), 1000.00, 100),
('customer2@test.com', '9835232369', 'Test Customer 2', '{PASSWORD_HASH}', true, true, NOW(), NOW(), 500.00, 50),
('owner@test.com', '9835232370', 'Test Owner', '{PASSWORD_HASH}', true, true, NOW(), NOW(), 0.00, 0),
('agent@test.com', '9835232371', 'Test Agent', '{PASSWORD_HASH}', true, true, NOW(), NOW(), 0.00, 0),
('admin@test.com', '9835232372', 'Test Admin', '{PASSWORD_HASH}', true, true, NOW(), NOW(), 0.00, 0)
ON CONFLICT (email) DO NOTHING;

-- Users (auth base for admin/agent/owner)
INSERT INTO users (role, active, email_verified, phone_verified, profile_completed, totp_enabled, created_at, updated_at) VALUES
('ADMIN', true, true, false, true, false, NOW(), NOW()),
('DELIVERY_AGENT', true, true, false, true, false, NOW(), NOW()),
('RESTAURANT_OWNER', true, true, false, true, false, NOW(), NOW())
ON CONFLICT DO NOTHING;

-- Admins
INSERT INTO admins (id, email, full_name, phone_number, password, created_at)
SELECT 1, 'admin@test.com', 'Test Admin', '9835232372', '{PASSWORD_HASH}', NOW()
WHERE EXISTS (SELECT 1 FROM users WHERE id = 1 AND role = 'ADMIN')
ON CONFLICT (id) DO NOTHING;

-- Delivery Agents
INSERT INTO delivery_agents (id, email, full_name, phone_number, available, verified, current_latitude, current_longitude, average_rating, total_deliveries, created_at, vehicle_type, vehicle_number, license_number)
SELECT 3, 'agent@test.com', 'Test Agent', '9835232371', true, true, 12.9716, 77.5946, 4.8, 150, NOW(), 'BIKE', 'KA01AB1234', 'DL123456'
WHERE EXISTS (SELECT 1 FROM users WHERE id = 3 AND role = 'DELIVERY_AGENT')
ON CONFLICT (id) DO NOTHING;

-- Restaurant Owners
INSERT INTO restaurant_owners (id, email, full_name, phone_number, business_license, verified, password, profile_image_url, totp_secret)
SELECT 2, 'owner@test.com', 'Test Owner', '9835232370', 'BL123456', true, '{PASSWORD_HASH}', NULL, NULL
WHERE EXISTS (SELECT 1 FROM users WHERE id = 2 AND role = 'RESTAURANT_OWNER')
ON CONFLICT (id) DO NOTHING;

-- Addresses
INSERT INTO addresses (customer_id, label, line1, city, state, zip_code, is_default, created_at, updated_at, latitude, longitude) VALUES
(1, 'Home', '123 MG Road', 'Bangalore', 'Karnataka', '560001', true, NOW(), NOW(), 12.9716, 77.5946),
(2, 'Office', '456 Brigade Road', 'Bangalore', 'Karnataka', '560025', true, NOW(), NOW(), 12.9753, 77.5908)
ON CONFLICT DO NOTHING;
""",
    "restaurants": """
-- Cuisines
INSERT INTO cuisines (name, image_url, active) VALUES
('North Indian', NULL, true),
('South Indian', NULL, true),
('Chinese', NULL, true),
('Italian', NULL, true),
('Desserts', NULL, true);

-- Restaurants
INSERT INTO restaurants (name, description, cuisine_id, address, phone, is_active, avg_rating, opening_time, closing_time, is_open, created_at, updated_at, owner_id) VALUES
('Spice Garden', 'Authentic North Indian restaurant', 1, '123 MG Road, Bangalore', '080-12345678', true, 4.5, '10:00:00', '23:00:00', true, NOW(), NOW(), 2),
('Dosa Hut', 'South Indian specialties', 2, '456 Brigade Road, Bangalore', '080-87654321', true, 4.3, '07:00:00', '22:00:00', true, NOW(), NOW(), 2),
('Pasta Paradise', 'Italian and continental', 4, '789 Indiranagar, Bangalore', '080-11223344', true, 4.7, '11:00:00', '00:00:00', true, NOW(), NOW(), 2);

-- Menu Categories
INSERT INTO menu_categories (name, description, restaurant_id, display_order, active) VALUES
('Starters', 'Delicious starters', 1, 1, true),
('Main Course', 'Main dishes', 1, 2, true),
('Desserts', 'Sweet treats', 1, 3, true),
('Breakfast', 'Morning specials', 2, 1, true),
('Lunch', 'Lunch combos', 2, 2, true),
('Pasta', 'Pasta dishes', 3, 1, true),
('Pizza', 'Pizza varieties', 3, 2, true);

-- Menu Items
INSERT INTO menu_items (name, description, price, is_available, restaurant_id, category_id, food_type, is_veg, is_spicy, spice_level, image_url, preparation_time, bestseller, recommended, average_rating, total_ratings, stock_quantity, created_at, updated_at) VALUES
('Butter Chicken', 'Creamy tomato curry', 320.00, true, 1, 2, 'NON_VEG', false, true, 'MEDIUM', 'https://example.com/bc.jpg', 25, true, true, 4.5, 100, 50, NOW(), NOW()),
('Dal Makhani', 'Black lentil curry', 280.00, true, 1, 2, 'VEG', true, false, 'MILD', 'https://example.com/dm.jpg', 20, false, true, 4.3, 80, 50, NOW(), NOW()),
('Garlic Naan', 'Fresh baked bread', 80.00, true, 1, 2, 'VEG', true, false, 'MILD', 'https://example.com/naan.jpg', 10, false, false, 4.0, 60, 100, NOW(), NOW()),
('Masala Dosa', 'Crispy rice crepe', 150.00, true, 2, 4, 'VEG', true, true, 'MEDIUM', 'https://example.com/dosa.jpg', 15, true, true, 4.6, 120, 80, NOW(), NOW()),
('Idli Vada', 'Steamed rice cakes', 120.00, true, 2, 4, 'VEG', true, false, 'MILD', 'https://example.com/idli.jpg', 10, false, false, 4.2, 90, 80, NOW(), NOW()),
('Margherita Pizza', 'Classic cheese pizza', 350.00, true, 3, 7, 'VEG', true, false, 'MILD', 'https://example.com/pizza.jpg', 20, true, true, 4.7, 150, 40, NOW(), NOW()),
('Pasta Alfredo', 'Creamy white sauce pasta', 300.00, true, 3, 6, 'VEG', true, false, 'MILD', 'https://example.com/alfredo.jpg', 25, false, true, 4.4, 110, 40, NOW(), NOW());

-- Reviews
INSERT INTO reviews (restaurant_id, customer_id, rating, food_rating, delivery_rating, comment, status, created_at, updated_at) VALUES
(1, 1, 5, 5, 4, 'Amazing food!', 'APPROVED', NOW(), NOW()),
(2, 1, 4, 4, 4, 'Good dosa', 'APPROVED', NOW(), NOW()),
(3, 2, 5, 5, 5, 'Best pizza in town', 'APPROVED', NOW(), NOW());
""",
    "orders": """
-- Orders (customer_id=1, restaurant_id=1)
INSERT INTO orders (order_number, customer_id, restaurant_id, status, total_amount, currency, subtotal, delivery_fee, discount_amount, loyalty_points_redeemed, delivery_address_id, special_instructions, fulfillment_type, created_at, updated_at) VALUES
('ORD001', 1, 1, 'DELIVERED', 500.00, 'INR', 460.00, 40.00, 0.00, 0, 1, 'Extra spicy', 'DELIVERY', NOW() - INTERVAL '2 hours', NOW() - INTERVAL '1 hour'),
('ORD002', 1, 2, 'OUT_FOR_DELIVERY', 350.00, 'INR', 320.00, 30.00, 0.00, 0, 2, NULL, 'DELIVERY', NOW() - INTERVAL '30 minutes', NOW() - INTERVAL '15 minutes'),
('ORD003', 2, 3, 'PREPARING', 600.00, 'INR', 550.00, 50.00, 0.00, 0, 1, 'Extra cheese', 'DELIVERY', NOW() - INTERVAL '10 minutes', NOW() - INTERVAL '5 minutes');

-- Order Items
INSERT INTO order_items (order_id, menu_item_id, item_name, unit_price, quantity, special_instructions, created_at) VALUES
(1, 1, 'Butter Chicken', 320.00, 2, 'Extra spicy', NOW()),
(1, 3, 'Garlic Naan', 80.00, 2, NULL, NOW()),
(2, 4, 'Masala Dosa', 150.00, 3, NULL, NOW()),
(3, 6, 'Margherita Pizza', 350.00, 1, 'Extra cheese', NOW()),
(3, 7, 'Pasta Alfredo', 300.00, 1, NULL, NOW());
""",
    "delivery": """
-- Delivery Zones
INSERT INTO delivery_zones (name, is_active, created_at) VALUES
('Bangalore Central', true, NOW()),
('Bangalore South', true, NOW()),
('Bangalore East', true, NOW());
""",
    "referral": """
-- Affiliate Codes
INSERT INTO affiliate_codes (code, name, channel, reward_amount, is_active, created_at) VALUES
('REF002', 'Test Referral 2', 'APP', 100.00, true, NOW());

-- Affiliate Referrals
INSERT INTO affiliate_referrals (affiliate_code_id, customer_id, customer_email, reward_amount, status, created_at) VALUES
(1, 2, 'customer2@test.com', 100.00, 'PENDING', NOW());

-- User Referral Codes
INSERT INTO user_referral_codes (customer_id, referral_code, referred_by, referrals_count, referral_bonus_earned, created_at) VALUES
(1, 'CUST1REF', NULL, 0, 0.0, NOW()),
(2, 'CUST2REF', 1, 0, 0.0, NOW())
ON CONFLICT (referral_code) DO NOTHING;
""",
    "notification": """
-- Notifications
INSERT INTO notifications (channel, recipient, template, subject, body, status, created_at, updated_at) VALUES
('EMAIL', 'customer1@test.com', 'order_confirmed', 'Order Confirmed', 'Your order has been confirmed', 'SENT', NOW(), NOW()),
('SMS', '9835232368', 'order_delivered', 'Order Delivered', 'Your order has been delivered', 'SENT', NOW(), NOW()),
('PUSH', 'customer2@test.com', 'promo', 'Weekend Offer', 'Check out our weekend specials', 'PENDING', NOW(), NOW());
""",
    "survey": """
-- Delivery Surveys
INSERT INTO delivery_surveys (order_id, customer_id, restaurant_id, rating_delivery, rating_food, rating_speed, comment, submitted_at) VALUES
(3, 2, 3, 5, 5, 5, 'Excellent!', NOW()),
(4, 1, 1, 4, 4, 3, 'Could be better', NOW());
""",
    "support": """
-- Support Tickets
INSERT INTO support_tickets (ticket_number, customer_id, order_id, category, subject, description, status, priority, created_at, updated_at) VALUES
('TKT001', 1, 1, 'ORDER', 'Order issue', 'Food was cold', 'OPEN', 'MEDIUM', NOW(), NOW()),
('TKT002', 2, 2, 'DELIVERY', 'Delivery delay', 'Delivered late', 'RESOLVED', 'LOW', NOW() - INTERVAL '1 day', NOW() - INTERVAL '1 day')
ON CONFLICT (ticket_number) DO NOTHING;
""",
    "payments": """
-- Payments
INSERT INTO payments (order_id, customer_id, amount, status, currency, created_at, updated_at) VALUES
(1, 1, 500.00, 'SUCCESS', 'INR', NOW() - INTERVAL '2 hours', NOW() - INTERVAL '2 hours'),
(2, 1, 350.00, 'SUCCESS', 'INR', NOW() - INTERVAL '30 minutes', NOW() - INTERVAL '30 minutes'),
(3, 2, 600.00, 'PENDING', 'INR', NOW() - INTERVAL '10 minutes', NOW() - INTERVAL '10 minutes');
""",
}

def seed_database(db_name, sql):
    cmd = [
        "psql", "-h", DB_HOST, "-p", DB_PORT, "-U", DB_USER, "-d", db_name,
        "-v", "ON_ERROR_STOP=1", "-c", sql
    ]
    try:
        result = subprocess.run(cmd, capture_output=True, text=True, timeout=30)
        if result.returncode == 0:
            print(f"  ✓ Seeded {db_name}")
            return True
        else:
            error_lines = result.stderr.strip().split('\n')[:3]
            print(f"  ✗ Failed to seed {db_name}:")
            for line in error_lines:
                print(f"    {line}")
            return False
    except Exception as e:
        print(f"  ✗ Error seeding {db_name}: {e}")
        return False

def main():
    print("Seeding databases with sample data...")
    success = 0
    for db_name, sql in SEEDS.items():
        print(f"Seeding {db_name}...")
        if seed_database(db_name, sql):
            success += 1
    print(f"\nSeeded {success}/{len(SEEDS)} databases")
    return 0 if success == len(SEEDS) else 1

if __name__ == "__main__":
    sys.exit(main())

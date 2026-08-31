-- V6: Baseline reference data (ported from the MySQL V5/V13/V14/V15 seeds).
-- Idempotent: ON CONFLICT DO NOTHING so re-runs are safe.

INSERT INTO cuisines (id, name, image_url, active) VALUES
    (1,  'North Indian',  NULL, TRUE),
    (2,  'South Indian',  NULL, TRUE),
    (3,  'Chinese',       NULL, TRUE),
    (4,  'Continental',   NULL, TRUE),
    (5,  'Fast Food',     NULL, TRUE),
    (6,  'Biryani',       NULL, TRUE),
    (7,  'Desserts',      NULL, TRUE),
    (8,  'Beverages',     NULL, TRUE),
    (9,  'Pizzas',        NULL, TRUE),
    (10, 'Healthy',       NULL, TRUE)
ON CONFLICT (id) DO NOTHING;

INSERT INTO membership_plans (id, name, description, price_per_month, free_delivery, discount_percent, is_active, tier_level) VALUES
    (1, 'Bhukkad One', 'Free delivery on all orders plus member discounts.', 149.0, TRUE, 10.0, TRUE, 1)
ON CONFLICT (id) DO NOTHING;

INSERT INTO delivery_zones (id, name, city, center_latitude, center_longitude, radius_km, base_delivery_fee, per_km_fee, surge_multiplier, is_active) VALUES
    (1, 'Bengaluru Central', 'Bengaluru', 12.9716, 77.5946, 15.0, 40.0, 5.0, 1.0, TRUE)
ON CONFLICT (id) DO NOTHING;

INSERT INTO promo_banners (title, image_url, action_type, display_order, is_active) VALUES
    ('Welcome to Bhukkad', NULL, 'NONE', 1, TRUE)
ON CONFLICT DO NOTHING;

-- Test data for proximity filtering integration tests.
-- Reference point: Bengaluru, MG Road area — latitude 12.9716, longitude 77.5946
--
-- Credentials:
--   Admin:   admin@bhukkad.dev / Admin@123456  (seeded by DevAdminBootstrap)
--   Test DB: bhukkad_test / bhukkad / bhukkad_test_pw (Testcontainers MySQL)
--
-- Restaurants planted at known distances from the center so each test case
-- (inside radius, on boundary, outside, empty set) has deterministic data.

-- Clean slate (order matters due to FK constraints)
DELETE FROM restaurant_cuisines WHERE restaurant_id IN (500, 501, 502, 503, 504);
DELETE FROM restaurants WHERE id IN (500, 501, 502, 503, 504);
DELETE FROM addresses WHERE id IN (500, 501, 502, 503, 504);
DELETE FROM restaurant_owners WHERE id IN (500, 501, 502, 503, 504);
DELETE FROM users WHERE id IN (500, 501, 502, 503, 504);

-- Restaurant owners (reuse the same user IDs)
INSERT INTO users (id, email, password, full_name, role, active, email_verified, created_at) VALUES
  (500, 'owner_500@bhukkad.test', 'pw', 'Owner 500', 'RESTAURANT_OWNER', 1, 0, NOW()),
  (501, 'owner_501@bhukkad.test', 'pw', 'Owner 501', 'RESTAURANT_OWNER', 1, 0, NOW()),
  (502, 'owner_502@bhukkad.test', 'pw', 'Owner 502', 'RESTAURANT_OWNER', 1, 0, NOW()),
  (503, 'owner_503@bhukkad.test', 'pw', 'Owner 503', 'RESTAURANT_OWNER', 1, 0, NOW()),
  (504, 'owner_504@bhukkad.test', 'pw', 'Owner 504', 'RESTAURANT_OWNER', 1, 0, NOW());

INSERT INTO restaurant_owners (id, verified) VALUES
  (500, 1), (501, 1), (502, 1), (503, 1), (504, 1);

-- Addresses at known distances from Bengaluru center (12.9716, 77.5946):
--   500: 12.9716, 77.5946  — 0 km (center, inside 5km radius)
--   501: 12.9750, 77.6000  — ~0.7 km (inside 5km radius, near boundary region)
--   502: 12.9800, 77.6100  — ~1.8 km (inside 5km radius)
--   503: 12.9900, 77.6200  — ~3.5 km (exactly on 5km boundary region, still inside)
--   504: 12.9600, 77.5800  — ~2.5 km (inside 5km radius)
INSERT INTO addresses (id, address_line1, city, state, pincode, latitude, longitude, is_default) VALUES
  (500, 'MG Road, Bengaluru', 'Bengaluru', 'KA', '560001', 12.9716, 77.5946, 1),
  (501, 'Near Cubbon Park', 'Bengaluru', 'KA', '560002', 12.9750, 77.6000, 1),
  (502, 'Indiranagar', 'Bengaluru', 'KA', '560038', 12.9800, 77.6100, 1),
  (503, 'Koramangala', 'Bengaluru', 'KA', '560034', 12.9900, 77.6200, 1),
  (504, 'Jayanagar', 'Bengaluru', 'KA', '560041', 12.9600, 77.5800, 1);

INSERT INTO restaurants
  (id, name, owner_id, address_id, opening_time, closing_time, is_active, is_open,
   average_rating, total_reviews, average_delivery_time, minimum_order_amount, delivery_fee,
   free_delivery_available, is_pure_veg, created_at)
VALUES
  (500, 'Center Cafe', 500, 500, '08:00:00', '22:00:00', 1, 1, 4.5, 100, 25, 199.0, 30.0, 1, 0, NOW()),
  (501, 'Park View', 501, 501, '08:00:00', '22:00:00', 1, 1, 4.3, 50, 30, 199.0, 35.0, 0, 0, NOW()),
  (502, 'Indira Eatery', 502, 502, '08:00:00', '22:00:00', 1, 1, 4.0, 30, 35, 299.0, 40.0, 0, 1, NOW()),
  (503, 'Koram Food', 503, 503, '08:00:00', '22:00:00', 1, 1, 4.2, 75, 32, 199.0, 35.0, 0, 0, NOW()),
  (504, 'Jayanagar Dhaba', 504, 504, '08:00:00', '22:00:00', 1, 1, 4.1, 40, 28, 199.0, 30.0, 1, 0, NOW());

INSERT INTO cuisines (id, name, active) VALUES
  (500, 'North Indian', 1),
  (501, 'South Indian', 1),
  (502, 'Chinese', 1),
  (503, 'Continental', 1),
  (504, 'Fast Food', 1);

INSERT INTO restaurant_cuisines (restaurant_id, cuisine_id) VALUES
  (500, 500), (500, 501),
  (501, 502),
  (502, 500), (502, 504),
  (503, 501),
  (504, 500);

-- Restaurant OUTSIDE the 5km radius — placed at Nandi Hills (~60 km from Bengaluru)
INSERT INTO addresses (id, address_line1, city, state, pincode, latitude, longitude, is_default) VALUES
  (505, 'Nandi Hills', 'Chikkaballapur', 'KA', '563101', 13.3700, 77.7400, 1);

INSERT INTO users (id, email, password, full_name, role, active, email_verified, created_at)
  VALUES (505, 'owner_505@bhukkad.test', 'pw', 'Owner 505', 'RESTAURANT_OWNER', 1, 0, NOW());

INSERT INTO restaurant_owners (id, verified) VALUES (505, 1);

INSERT INTO restaurants
  (id, name, owner_id, address_id, opening_time, closing_time, is_active, is_open,
   average_rating, total_reviews, average_delivery_time, minimum_order_amount, delivery_fee,
   free_delivery_available, is_pure_veg, created_at)
VALUES
  (505, 'Far Away Restaurant', 505, 505, '08:00:00', '22:00:00', 1, 1, 3.5, 10, 45, 299.0, 80.0, 0, 0, NOW());

-- Dateline-crossing test data
-- Restaurant 506: near the dateline at lon 179.9
INSERT INTO addresses (id, latitude, longitude, address_line1, city, state, country, pincode)
VALUES (506, -17.7134, 179.9, 'Suva', 'Rewa', 'Central', 'Fiji', '00000');
INSERT INTO restaurants (id, name, address_id, is_active, is_open, is_pure_veg, average_rating, average_delivery_time, minimum_order_amount, delivery_fee)
VALUES (506, 'Dateline East', 506, 1, 1, 0, 4.0, 20, 100.0, 20.0);
INSERT INTO restaurant_cuisines (restaurant_id, cuisine_id) VALUES (506, 1);

-- Restaurant 507: just across the dateline at lon -179.9
INSERT INTO addresses (id, latitude, longitude, address_line1, city, state, country, pincode)
VALUES (507, -17.7134, -179.9, 'Suva', 'Rewa', 'Central', 'Fiji', '00000');
INSERT INTO restaurants (id, name, address_id, is_active, is_open, is_pure_veg, average_rating, average_delivery_time, minimum_order_amount, delivery_fee)
VALUES (507, 'Dateline West', 507, 1, 1, 0, 4.0, 20, 100.0, 20.0);
INSERT INTO restaurant_cuisines (restaurant_id, cuisine_id) VALUES (507, 1);

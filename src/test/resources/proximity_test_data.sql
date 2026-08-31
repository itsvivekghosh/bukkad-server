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
DELETE FROM restaurant_cuisines WHERE restaurant_id IN (500, 501, 502, 503, 504, 505, 506, 507);
DELETE FROM cuisines WHERE id IN (500, 501, 502, 503, 504);
DELETE FROM restaurants WHERE id IN (500, 501, 502, 503, 504, 505, 506, 507);
DELETE FROM addresses WHERE id IN (500, 501, 502, 503, 504, 505, 506, 507);
DELETE FROM restaurant_owners WHERE id IN (500, 501, 502, 503, 504, 505, 506, 507);
DELETE FROM users WHERE id IN (500, 501, 502, 503, 504, 505, 506, 507);

-- Restaurant owners (reuse the same user IDs; V62 moved PII to role tables)
INSERT INTO users (id, role, active, email_verified, created_at) VALUES
  (500, 'RESTAURANT_OWNER', TRUE, FALSE, CURRENT_TIMESTAMP),
  (501, 'RESTAURANT_OWNER', TRUE, FALSE, CURRENT_TIMESTAMP),
  (502, 'RESTAURANT_OWNER', TRUE, FALSE, CURRENT_TIMESTAMP),
  (503, 'RESTAURANT_OWNER', TRUE, FALSE, CURRENT_TIMESTAMP),
  (504, 'RESTAURANT_OWNER', TRUE, FALSE, CURRENT_TIMESTAMP);

INSERT INTO restaurant_owners (id, verified, email, password, full_name) VALUES
  (500, TRUE, 'owner_500@bhukkad.test', 'pw', 'Owner 500'),
  (501, TRUE, 'owner_501@bhukkad.test', 'pw', 'Owner 501'),
  (502, TRUE, 'owner_502@bhukkad.test', 'pw', 'Owner 502'),
  (503, TRUE, 'owner_503@bhukkad.test', 'pw', 'Owner 503'),
  (504, TRUE, 'owner_504@bhukkad.test', 'pw', 'Owner 504');

-- Addresses at known distances from Bengaluru center (12.9716, 77.5946):
--   500: 12.9716, 77.5946  — 0 km (center, inside 5km radius)
--   501: 12.9750, 77.6000  — ~0.7 km (inside 5km radius, near boundary region)
--   502: 12.9800, 77.6100  — ~1.8 km (inside 5km radius)
--   503: 12.9900, 77.6200  — ~3.5 km (exactly on 5km boundary region, still inside)
--   504: 12.9600, 77.5800  — ~2.5 km (inside 5km radius)
INSERT INTO addresses (id, address_line1, city, state, pincode, latitude, longitude, is_default) VALUES
  (500, 'MG Road, Bengaluru', 'Bengaluru', 'KA', '560001', 12.9716, 77.5946, TRUE),
  (501, 'Near Cubbon Park', 'Bengaluru', 'KA', '560002', 12.9750, 77.6000, TRUE),
  (502, 'Indiranagar', 'Bengaluru', 'KA', '560038', 12.9800, 77.6100, TRUE),
  (503, 'Koramangala', 'Bengaluru', 'KA', '560034', 12.9900, 77.6200, TRUE),
  (504, 'Jayanagar', 'Bengaluru', 'KA', '560041', 12.9600, 77.5800, TRUE);

INSERT INTO restaurants
  (id, name, owner_id, address_id, opening_time, closing_time, is_active, is_open,
   average_rating, total_reviews, average_delivery_time, minimum_order_amount, delivery_fee,
   free_delivery_available, is_pure_veg, created_at)
VALUES
  (500, 'Center Cafe', 500, 500, '08:00:00', '22:00:00', TRUE, TRUE, 4.5, 100, 25, 199.0, 30.0, TRUE, FALSE, CURRENT_TIMESTAMP),
  (501, 'Park View', 501, 501, '08:00:00', '22:00:00', TRUE, TRUE, 4.3, 50, 30, 199.0, 35.0, FALSE, FALSE, CURRENT_TIMESTAMP),
  (502, 'Indira Eatery', 502, 502, '08:00:00', '22:00:00', TRUE, TRUE, 4.0, 30, 35, 299.0, 40.0, FALSE, TRUE, CURRENT_TIMESTAMP),
  (503, 'Koram Food', 503, 503, '08:00:00', '22:00:00', TRUE, TRUE, 4.2, 75, 32, 199.0, 35.0, FALSE, FALSE, CURRENT_TIMESTAMP),
  (504, 'Jayanagar Dhaba', 504, 504, '08:00:00', '22:00:00', TRUE, TRUE, 4.1, 40, 28, 199.0, 30.0, TRUE, FALSE, CURRENT_TIMESTAMP);

INSERT INTO cuisines (id, name, active) VALUES
  (500, 'Proximity North Indian', TRUE),
  (501, 'Proximity South Indian', TRUE),
  (502, 'Proximity Chinese', TRUE),
  (503, 'Proximity Continental', TRUE),
  (504, 'Proximity Fast Food', TRUE);

INSERT INTO restaurant_cuisines (restaurant_id, cuisine_id) VALUES
  (500, 500), (500, 501),
  (501, 502),
  (502, 500), (502, 504),
  (503, 501),
  (504, 500);

-- Restaurant OUTSIDE the 5km radius — placed at Nandi Hills (~60 km from Bengaluru)
INSERT INTO addresses (id, address_line1, city, state, pincode, latitude, longitude, is_default) VALUES
  (505, 'Nandi Hills', 'Chikkaballapur', 'KA', '563101', 13.3700, 77.7400, TRUE);

INSERT INTO users (id, role, active, email_verified, created_at)
  VALUES (505, 'RESTAURANT_OWNER', TRUE, FALSE, CURRENT_TIMESTAMP);

INSERT INTO restaurant_owners (id, verified, email, password, full_name) VALUES (505, TRUE, 'owner_505@bhukkad.test', 'pw', 'Owner 505');

INSERT INTO restaurants
  (id, name, owner_id, address_id, opening_time, closing_time, is_active, is_open,
   average_rating, total_reviews, average_delivery_time, minimum_order_amount, delivery_fee,
   free_delivery_available, is_pure_veg, created_at)
VALUES
  (505, 'Far Away Restaurant', 505, 505, '08:00:00', '22:00:00', TRUE, TRUE, 3.5, 10, 45, 299.0, 80.0, FALSE, FALSE, CURRENT_TIMESTAMP);

-- Dateline-crossing test data
-- Restaurant 506: near the dateline at lon 179.9
INSERT INTO users (id, role, active, email_verified, created_at)
  VALUES (506, 'RESTAURANT_OWNER', TRUE, FALSE, CURRENT_TIMESTAMP);
INSERT INTO restaurant_owners (id, verified, email, password, full_name) VALUES (506, TRUE, 'owner_506@bhukkad.test', 'pw', 'Owner 506');
INSERT INTO addresses (id, latitude, longitude, address_line1, city, state, pincode)
VALUES (506, -17.7134, 179.9, 'Suva', 'Rewa', 'Central', '00000');
INSERT INTO restaurants (id, name, owner_id, address_id, opening_time, closing_time, is_active, is_open, is_pure_veg, average_rating, average_delivery_time, minimum_order_amount, delivery_fee, created_at)
VALUES (506, 'Dateline East', 506, 506, '08:00:00', '22:00:00', TRUE, TRUE, FALSE, 4.0, 20, 100.0, 20.0, CURRENT_TIMESTAMP);
INSERT INTO restaurant_cuisines (restaurant_id, cuisine_id) VALUES (506, 500);

-- Restaurant 507: just across the dateline at lon -179.9
INSERT INTO users (id, role, active, email_verified, created_at)
  VALUES (507, 'RESTAURANT_OWNER', TRUE, FALSE, CURRENT_TIMESTAMP);
INSERT INTO restaurant_owners (id, verified, email, password, full_name) VALUES (507, TRUE, 'owner_507@bhukkad.test', 'pw', 'Owner 507');
INSERT INTO addresses (id, latitude, longitude, address_line1, city, state, pincode)
VALUES (507, -17.7134, -179.9, 'Suva', 'Rewa', 'Central', '00000');
INSERT INTO restaurants (id, name, owner_id, address_id, opening_time, closing_time, is_active, is_open, is_pure_veg, average_rating, average_delivery_time, minimum_order_amount, delivery_fee, created_at)
VALUES (507, 'Dateline West', 507, 507, '08:00:00', '22:00:00', TRUE, TRUE, FALSE, 4.0, 20, 100.0, 20.0, CURRENT_TIMESTAMP);
INSERT INTO restaurant_cuisines (restaurant_id, cuisine_id) VALUES (507, 500);

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

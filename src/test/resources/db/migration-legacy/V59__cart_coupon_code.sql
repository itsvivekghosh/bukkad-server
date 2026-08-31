-- Persist the coupon code applied to a cart so it survives across get-cart
-- and is available at checkout time. The discount itself is calculated
-- server-side from the coupon rules (OrderPricingServiceImpl), but the
-- cart-level response includes the computed discount so the client can
-- show the reduced total before order placement.

ALTER TABLE carts ADD COLUMN coupon_code VARCHAR(50) NULL AFTER restaurant_id;
CREATE INDEX idx_cart_coupon_code ON carts (coupon_code);

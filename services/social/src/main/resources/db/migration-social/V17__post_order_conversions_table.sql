-- Post-to-order conversion tracking table
-- Records when a social post leads to an order for analytics and optimization
CREATE TABLE IF NOT EXISTS post_order_conversions (
    id BIGSERIAL PRIMARY KEY,
    post_id BIGINT NOT NULL,
    order_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    restaurant_id BIGINT NOT NULL,
    conversion_timestamp TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    post_author_id BIGINT,
    post_content TEXT,
    post_media_urls TEXT[],
    post_post_type VARCHAR(50),
    items_count INTEGER NOT NULL,
    total_amount NUMERIC(15, 2) NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_post_order_conversions_post_id ON post_order_conversions(post_id);
CREATE INDEX IF NOT EXISTS idx_post_order_conversions_order_id ON post_order_conversions(order_id);
CREATE INDEX IF NOT EXISTS idx_post_order_conversions_user_id ON post_order_conversions(user_id);
CREATE INDEX IF NOT EXISTS idx_post_order_conversions_restaurant_id ON post_order_conversions(restaurant_id);
CREATE INDEX IF NOT EXISTS idx_post_order_conversions_timestamp ON post_order_conversions(conversion_timestamp);
CREATE INDEX IF NOT EXISTS idx_post_order_conversions_post_timestamp ON post_order_conversions(post_id, conversion_timestamp DESC);
COMMENT ON TABLE post_order_conversions IS 'Tracks conversions from social posts to orders for analytics and optimization';

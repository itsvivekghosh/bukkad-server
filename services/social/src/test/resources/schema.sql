-- Social service test schema
-- This schema is used for integration tests

-- Social posts table
CREATE TABLE IF NOT EXISTS social_posts (
    id BIGSERIAL PRIMARY KEY,
    restaurant_id BIGINT NOT NULL,
    author_id BIGINT NOT NULL,
    content TEXT NOT NULL,
    post_type VARCHAR(50),
    like_count INTEGER DEFAULT 0,
    comment_count INTEGER DEFAULT 0,
    author_name VARCHAR(255),
    restaurant_name VARCHAR(255),
    latitude DOUBLE PRECISION,
    longitude DOUBLE PRECISION,
    media_urls TEXT[],
    status VARCHAR(50) NOT NULL DEFAULT 'active',
    deleted_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Post likes table
CREATE TABLE IF NOT EXISTS post_likes (
    post_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (post_id, user_id)
);

-- Post comments table
CREATE TABLE IF NOT EXISTS post_comments (
    id BIGSERIAL PRIMARY KEY,
    post_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    parent_comment_id BIGINT,
    content VARCHAR(1000) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Post order conversions table
CREATE TABLE IF NOT EXISTS post_order_conversions (
    id BIGSERIAL PRIMARY KEY,
    post_id BIGINT NOT NULL,
    restaurant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    order_id BIGINT NOT NULL,
    conversion_timestamp TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Outbox events table
CREATE TABLE IF NOT EXISTS outbox_events (
    id BIGSERIAL PRIMARY KEY,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id BIGINT NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    retry_count INTEGER DEFAULT 0,
    last_error TEXT,
    next_attempt_at TIMESTAMP,
    processing_started_at TIMESTAMP,
    published_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    partition_id INTEGER
);

-- Idempotency records table
CREATE TABLE IF NOT EXISTS idempotency_records (
    id BIGSERIAL PRIMARY KEY,
    scope VARCHAR(50) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    owner_id BIGINT,
    status VARCHAR(50) NOT NULL DEFAULT 'IN_PROGRESS',
    request_hash VARCHAR(64),
    response_payload TEXT,
    expires_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (scope, idempotency_key)
);

-- Create indexes
CREATE INDEX IF NOT EXISTS idx_social_posts_restaurant_created ON social_posts (restaurant_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_social_posts_author_created ON social_posts (author_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_post_likes_post_id ON post_likes (post_id);
CREATE INDEX IF NOT EXISTS idx_post_comments_post_created ON post_comments (post_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_post_order_conversions_post ON post_order_conversions (post_id, conversion_timestamp DESC);
CREATE INDEX IF NOT EXISTS idx_outbox_events_status_created ON outbox_events (status, created_at);
CREATE INDEX IF NOT EXISTS idx_idempotency_scope_key ON idempotency_records (scope, idempotency_key);

-- =============================================================================
-- Social service baseline schema: posts, likes, comments
-- =============================================================================

CREATE TABLE IF NOT EXISTS social_posts (
    id BIGSERIAL PRIMARY KEY,
    restaurant_id BIGINT NOT NULL,
    author_id BIGINT NOT NULL,
    content TEXT NOT NULL,
    media_urls TEXT[] DEFAULT '{}',
    post_type VARCHAR(50) DEFAULT 'update',
    status VARCHAR(20) DEFAULT 'active' NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    updated_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    like_count INT DEFAULT 0 NOT NULL,
    comment_count INT DEFAULT 0 NOT NULL,
    deleted_at TIMESTAMPTZ,
    author_name VARCHAR(255),
    restaurant_name VARCHAR(255),
    latitude DOUBLE PRECISION,
    longitude DOUBLE PRECISION
);

CREATE TABLE IF NOT EXISTS post_likes (
    id BIGSERIAL PRIMARY KEY,
    post_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW() NOT NULL,
    CONSTRAINT uq_post_likes_post_user UNIQUE (post_id, user_id)
);

CREATE TABLE IF NOT EXISTS post_comments (
    id BIGSERIAL PRIMARY KEY,
    post_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    parent_comment_id BIGINT,
    content TEXT NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW() NOT NULL
);

-- Indexes
CREATE INDEX IF NOT EXISTS idx_social_posts_restaurant_created
    ON social_posts (restaurant_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_social_posts_author_created
    ON social_posts (author_id, created_at DESC)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_post_likes_post_created
    ON post_likes (post_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_post_comments_post_created
    ON post_comments (post_id, created_at DESC)
    WHERE parent_comment_id IS NULL;

ANALYZE social_posts;
ANALYZE post_likes;
ANALYZE post_comments;

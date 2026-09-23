-- =============================================================================
-- Social service performance indexes for geospatial and list queries
-- =============================================================================

-- Composite btree index covering the bounding-box + status filter in
-- findActivePostsInBounds and findActivePostsWithinRadius.
CREATE INDEX IF NOT EXISTS idx_social_posts_geospatial
    ON social_posts (status, deleted_at, latitude, longitude, created_at DESC);

-- Index for order-from-post lookups by restaurant_id
CREATE INDEX IF NOT EXISTS idx_social_posts_restaurant_active_created
    ON social_posts (restaurant_id, created_at DESC)
    WHERE status = 'active' AND deleted_at IS NULL;

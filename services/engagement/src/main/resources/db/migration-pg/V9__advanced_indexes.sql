-- =============================================================================
-- Bhukkad Engagement Service - Advanced Performance Indexes
-- Version: 9 | Date: 2026-11-12
-- 
-- Purpose: Advanced composite indexes for all domains to support optimized
-- query patterns from Phase 1 and Phase 2 optimizations.
-- =============================================================================

-- =============================================================================
-- CAMPAIGN DOMAIN INDEXES
-- =============================================================================

-- Campaign lookups by status and time range (for getActiveCampaigns)
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_promotion_campaign_status_time 
ON promotion_campaigns(is_active, starts_at, ends_at) 
WHERE is_active = TRUE;

-- Campaign discount calculations (for calculateBestDiscount)
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_promotion_campaign_active_min_order 
ON promotion_campaigns(is_active, min_order_amount) 
WHERE is_active = TRUE AND min_order_amount IS NOT NULL;

-- Campaign by ID lookup
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_promotion_campaign_id_active 
ON promotion_campaigns(id, is_active);

-- =============================================================================
-- LOYALTY DOMAIN INDEXES
-- =============================================================================

-- Loyalty balance lookups by tier (for tier-based marketing queries)
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_loyalty_point_balance_tier_points 
ON loyalty_point_balances(tier_level, points DESC) 
WHERE points > 0;

-- Loyalty ledger balance queries (time-range per customer)
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_loyalty_ledger_customer_time 
ON loyalty_points_ledger(customer_id, created_at DESC) 
INCLUDE (points, event_type);

-- Loyalty ledger by reference id (idempotency checks)
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_loyalty_ledger_reference_id 
ON loyalty_points_ledger(reference_id) 
WHERE reference_id IS NOT NULL;

-- =============================================================================
-- SOCIAL DOMAIN INDEXES
-- =============================================================================

-- Social posts geospatial queries with status filter
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_social_post_location_status 
ON social_posts USING GIST(location) 
WHERE status = 'ACTIVE';

-- Social posts by restaurant with time ordering
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_social_post_restaurant_created 
ON social_posts(restaurant_id, created_at DESC) 
WHERE status = 'ACTIVE';

-- Social posts by author
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_social_post_author_created 
ON social_posts(author_id, created_at DESC);

-- Like counts lookups
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_post_likes_post_user 
ON post_likes(post_id, user_id) 
WHERE created_at > NOW() - INTERVAL '30 days';

-- Like count aggregation by post
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_post_likes_post_count 
ON post_likes(post_id) 
WHERE created_at > NOW() - INTERVAL '30 days';

-- =============================================================================
-- NOTIFICATION DOMAIN INDEXES
-- =============================================================================

-- Notification recipient history queries
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_notification_recipient_channel_time 
ON notifications(recipient, channel, created_at DESC) 
WHERE status IN ('SENT', 'FAILED', 'PENDING');

-- Notification by status for retry processing
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_notification_status_created 
ON notifications(status, created_at) 
WHERE status = 'PENDING';

-- =============================================================================
-- REALTIME DOMAIN INDEXES
-- =============================================================================

-- Live event store replay queries
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_live_event_store_stream_seq 
ON live_event_store(stream_key, sequence_id DESC) 
WHERE created_at > NOW() - INTERVAL '7 days';

-- Live event store by event type and time
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_live_event_store_type_time 
ON live_event_store(event_type, created_at DESC) 
WHERE created_at > NOW() - INTERVAL '7 days';

-- =============================================================================
-- REFERRAL DOMAIN INDEXES
-- =============================================================================

-- Referral records by referred customer (idempotency checks)
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_referral_record_referred_customer 
ON referral_records(referred_customer_id) 
WHERE referred_customer_id IS NOT NULL;

-- Referral records by referrer (stats queries)
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_referral_record_referrer_status 
ON referral_records(referrer_id, status) 
WHERE status = 'COMPLETED';

-- Referral codes lookup
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_user_referral_code_customer 
ON user_referral_codes(customer_id) 
INCLUDE (referral_code, referred_by);

-- =============================================================================
-- CHAT / FRAUD DOMAIN INDEXES
-- =============================================================================

-- Chat session messages with fraud flag
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_chat_message_session_fraud 
ON chat_messages(session_id, is_fraud_flagged DESC, created_at DESC) 
WHERE is_fraud_flagged = TRUE;

-- Fraud case investigation queries
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_fraud_case_investigation 
ON fraud_cases(assigned_to, status, risk_score DESC) 
WHERE status IN ('DETECTED', 'REVIEWED');

-- Fraud cases by high risk score (dashboard)
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_fraud_case_high_risk 
ON fraud_cases(risk_score DESC, created_at DESC) 
WHERE risk_score >= 50;

-- Fraud cases by session
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_fraud_case_session 
ON fraud_cases(session_id);

-- Attachment duplicate detection (batch query optimization)
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_attachments_hash_analyzed 
ON attachments(image_hash, analysis_status) 
WHERE image_hash IS NOT NULL;

-- Chat sessions by department and status
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_chat_sessions_dept_status 
ON chat_sessions(department, status, created_at DESC);

-- Chat sessions by order ID (delivery/order specific queries)
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_chat_sessions_order_closed 
ON chat_sessions(order_id, status) 
WHERE order_id IS NOT NULL AND status IN ('OPEN', 'ESCALATED');

-- =============================================================================
-- SURVEY DOMAIN INDEXES
-- =============================================================================

-- Survey by order id (duplicate check)
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_delivery_survey_order_id 
ON delivery_surveys(order_id);

-- Survey restaurant averages (aggregation queries)
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_delivery_survey_restaurant_time 
ON delivery_surveys(restaurant_id, submitted_at DESC);

-- Trending dishes by quantity sold
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_trending_dish_quantity_sold 
ON trending_dishes(quantity_sold DESC);

-- =============================================================================
-- PARTIAL INDEXES FOR COMMON QUERY PATTERNS
-- =============================================================================

-- Active chat sessions only
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_chat_sessions_active_only 
ON chat_sessions(customer_id, created_at DESC) 
WHERE status = 'OPEN';

-- High risk fraud cases only (partial index for dashboards)
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_fraud_case_high_risk_only 
ON fraud_cases(case_id, fraud_type, risk_score) 
WHERE risk_score >= 75 AND status = 'DETECTED';

-- Pending notifications only
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_notification_pending_only 
ON notifications(created_at, recipient) 
WHERE status = 'PENDING';

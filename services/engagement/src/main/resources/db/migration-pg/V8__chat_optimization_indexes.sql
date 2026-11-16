-- =============================================================================
-- Chat Support System - Performance Optimization Indexes
-- Version: 8 | Date: 2026-11-12
-- 
-- Purpose: Optimize common query patterns in chat support and fraud detection
-- =============================================================================

-- Composite index for fraud case statistics by status and fraud type
CREATE INDEX IF NOT EXISTS idx_fraud_cases_status_type ON fraud_cases(status, fraud_type);

-- Partial index for high-risk fraud cases (for dashboard queries)
CREATE INDEX IF NOT EXISTS idx_fraud_cases_high_risk ON fraud_cases(risk_score DESC, created_at DESC)
WHERE risk_score >= 50;

-- Composite index for chat messages with fraud filtering
CREATE INDEX IF NOT EXISTS idx_chat_messages_session_fraud ON chat_messages(session_id, is_fraud_flagged DESC, created_at DESC);

-- Composite index for attachments with image hash for batch duplicate detection
CREATE INDEX IF NOT EXISTS idx_attachments_hash_analyzed ON attachments(image_hash, analysis_status)
WHERE image_hash IS NOT NULL;

-- Composite index for session filtering by department and status
CREATE INDEX IF NOT EXISTS idx_chat_sessions_dept_status ON chat_sessions(department, status, created_at DESC);

-- Index for chat session audit with actor role filtering
CREATE INDEX IF NOT EXISTS idx_session_audit_action_role ON chat_session_audit(action, actor_role, created_at DESC);

-- Index for fraud scores cache with risk level and expiration
CREATE INDEX IF NOT EXISTS idx_fraud_score_cache_risk_expires ON fraud_score_cache(risk_level, expires_at);

-- Partial index for pending attachments to optimize background processing
CREATE INDEX IF NOT EXISTS idx_attachments_pending ON attachments(created_at)
WHERE analysis_status = 'PENDING';

-- Index for fraud flag searches
CREATE INDEX IF NOT EXISTS idx_chat_messages_fraud_flagged ON chat_messages(is_fraud_flagged)
WHERE is_fraud_flagged = TRUE;

-- Composite index for fraud cases assigned to support agents
CREATE INDEX IF NOT EXISTS idx_fraud_cases_assigned ON fraud_cases(assigned_to, status)
WHERE assigned_to IS NOT NULL;

-- Composite index for chat sessions with order IDs (delivery/order specific queries)
CREATE INDEX IF NOT EXISTS idx_chat_sessions_order_closed ON chat_sessions(order_id, status)
WHERE order_id IS NOT NULL AND status IN ('OPEN', 'ESCALATED');
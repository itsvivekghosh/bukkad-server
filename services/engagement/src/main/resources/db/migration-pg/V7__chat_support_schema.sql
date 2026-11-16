-- =============================================================================
-- Chat Support System Schema for Restaurant Operations
-- Version: 7 | Date: 2026-11-12
-- =============================================================================
-- Tables:
--   1. chat_sessions     - Conversation threads
--   2. chat_messages     - Individual messages
--   3. attachments       - Multimedia attachments
--   4. fraud_cases       - Detected fraud records
--   5. fraud_scores      - Real-time scoring cache
-- =============================================================================

-- Chat Sessions: Tracks conversation threads between customers and support
CREATE TABLE chat_sessions (
    id                      BIGSERIAL PRIMARY KEY,
    session_id              UUID UNIQUE NOT NULL DEFAULT gen_random_uuid(),
    customer_id             BIGINT NOT NULL,
    order_id                BIGINT,
    department              VARCHAR(50) NOT NULL CHECK (department IN ('ORDER', 'DELIVERY', 'GENERAL', 'PAYMENT')),
    subject                 VARCHAR(200),
    status                  VARCHAR(20) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'RESOLVED', 'ESCALATED', 'CLOSED', 'ABANDONED')),
    priority                VARCHAR(10) NOT NULL DEFAULT 'NORMAL' CHECK (priority IN ('LOW', 'NORMAL', 'HIGH', 'URGENT')),
    created_at              TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    closed_at               TIMESTAMP WITH TIME ZONE,
    satisfaction_rating     SMALLINT CHECK (satisfaction_rating BETWEEN 1 AND 5),
    satisfaction_comment    TEXT,

    -- Indexes for fast lookups
    CONSTRAINT fk_chat_sessions_customer FOREIGN KEY (customer_id) REFERENCES customers(id) ON DELETE CASCADE,
    CONSTRAINT fk_chat_sessions_order FOREIGN KEY (order_id) REFERENCES orders(id) ON DELETE SET NULL
);

-- Index for session lookups
CREATE INDEX idx_chat_sessions_customer_id ON chat_sessions(customer_id);
CREATE INDEX idx_chat_sessions_order_id ON chat_sessions(order_id);
CREATE INDEX idx_chat_sessions_status ON chat_sessions(status);
CREATE INDEX idx_chat_sessions_created_at ON chat_sessions(created_at DESC);

-- Chat Messages: Individual messages within sessions
CREATE TABLE chat_messages (
    id                      BIGSERIAL PRIMARY KEY,
    message_id              UUID UNIQUE NOT NULL DEFAULT gen_random_uuid(),
    session_id              UUID NOT NULL,
    sender_id               BIGINT NOT NULL,
    sender_role             VARCHAR(20) NOT NULL CHECK (sender_role IN ('CUSTOMER', 'SUPPORT_AGENT', 'SYSTEM')),
    message_type            VARCHAR(20) NOT NULL DEFAULT 'TEXT' CHECK (message_type IN ('TEXT', 'IMAGE', 'FILE', 'SYSTEM', 'ACTION')),
    content                 TEXT,
    content_type            VARCHAR(100),
    reply_to_message_id     UUID,
    sentiment_score         SMALLINT CHECK (sentiment_score BETWEEN -100 AND 100),
    is_fraud_flagged        BOOLEAN NOT NULL DEFAULT FALSE,
    fraud_reason            TEXT,
    created_at              TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_chat_messages_session FOREIGN KEY (session_id) REFERENCES chat_sessions(session_id) ON DELETE CASCADE,
    CONSTRAINT fk_chat_messages_sender FOREIGN KEY (sender_id) REFERENCES customers(id) ON DELETE CASCADE
);

-- Indexes for message queries
CREATE INDEX idx_chat_messages_session_id ON chat_messages(session_id);
CREATE INDEX idx_chat_messages_created_at ON chat_messages(created_at);
CREATE INDEX idx_chat_messages_sender_id ON chat_messages(sender_id);
CREATE INDEX idx_chat_messages_fraud_flagged ON chat_messages(is_fraud_flagged) WHERE is_fraud_flagged = TRUE;

-- Attachments: Multimedia files (photos, receipts) for evidence
CREATE TABLE attachments (
    id                      BIGSERIAL PRIMARY KEY,
    attachment_id           UUID UNIQUE NOT NULL DEFAULT gen_random_uuid(),
    message_id              UUID NOT NULL,
    file_name               VARCHAR(255) NOT NULL,
    file_size_bytes         BIGINT NOT NULL CHECK (file_size_bytes > 0),
    mime_type               VARCHAR(100) NOT NULL,
    content_type            VARCHAR(50) CHECK (content_type IN ('ORIGINAL', 'THUMBNAIL', 'PROCESSED')),
    storage_key             VARCHAR(500) NOT NULL,
    storage_provider        VARCHAR(50) NOT NULL DEFAULT 'S3' CHECK (storage_provider IN ('S3', 'GCS', 'LOCAL')),
    
    -- Image EXIF/Media metadata (for fraud detection)
    image_width             INTEGER,
    image_height            INTEGER,
    exif_make               VARCHAR(100),
    exif_model              VARCHAR(100),
    exif_datetime_original  TIMESTAMP WITH TIME ZONE,
    gps_latitude            DECIMAL(10, 8),
    gps_longitude           DECIMAL(11, 8),
    compression_ratio       DECIMAL(5, 2),
    
    -- Fraud detection metadata
    is_duplicate_fingerprint  BOOLEAN NOT NULL DEFAULT FALSE,
    duplicate_of_attachment_id UUID,
    image_hash               VARCHAR(64),
    analysis_status          VARCHAR(20) DEFAULT 'PENDING' CHECK (analysis_status IN ('PENDING', 'ANALYZED', 'FLAGGED', 'CLEARED')),
    fraud_risk_score         DECIMAL(5, 2) CHECK (fraud_risk_score BETWEEN 0 AND 100),
    fraud_details            JSONB,
    
    created_at              TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    processed_at            TIMESTAMP WITH TIME ZONE,

    CONSTRAINT fk_attachments_message FOREIGN KEY (message_id) REFERENCES chat_messages(message_id) ON DELETE CASCADE,
    CONSTRAINT fk_attachments_duplicate FOREIGN KEY (duplicate_of_attachment_id) REFERENCES attachments(attachment_id) ON DELETE SET NULL
);

-- Indexes for attachment queries
CREATE INDEX idx_attachments_message_id ON attachments(message_id);
CREATE INDEX idx_attachments_image_hash ON attachments(image_hash);
CREATE INDEX idx_attachments_exif_datetime ON attachments(exif_datetime_original);
CREATE INDEX idx_attachments_analysis_status ON attachments(analysis_status);
CREATE INDEX idx_attachments_fraud_score ON attachments(fraud_risk_score DESC);
CREATE INDEX idx_attachments_storage_key ON attachments(storage_key);

-- Fraud Cases: Record of detected fraud incidents
CREATE TABLE fraud_cases (
    id                      BIGSERIAL PRIMARY KEY,
    case_id                 UUID UNIQUE NOT NULL DEFAULT gen_random_uuid(),
    session_id              UUID NOT NULL,
    message_id              UUID,
    attachment_id           UUID,
    fraud_type              VARCHAR(50) NOT NULL CHECK (fraud_type IN ('FAKE_ORDER_CLAIM', 'REFUND_SCAM', 'IDENTITY_SPOOFING', 'ATTACHMENT_MANIPULATION', 'BEHAVIORAL_ANOMALY')),
    risk_score              DECIMAL(5, 2) NOT NULL CHECK (risk_score BETWEEN 0 AND 100),
    confidence_level        VARCHAR(20) NOT NULL CHECK (confidence_level IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    status                  VARCHAR(20) NOT NULL DEFAULT 'DETECTED' CHECK (status IN ('DETECTED', 'REVIEWED', 'CONFIRMED', 'DISMISSED', 'ACTION_TAKEN')),
    detection_method        VARCHAR(100) NOT NULL,
    flags_found             JSONB,
    details                 JSONB,
    assigned_to             BIGINT,
    resolution_notes        TEXT,
    created_at              TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    resolved_at             TIMESTAMP WITH TIME ZONE,

    CONSTRAINT fk_fraud_cases_session FOREIGN KEY (session_id) REFERENCES chat_sessions(session_id) ON DELETE CASCADE,
    CONSTRAINT fk_fraud_cases_message FOREIGN KEY (message_id) REFERENCES chat_messages(message_id) ON DELETE SET NULL,
    CONSTRAINT fk_fraud_cases_attachment FOREIGN KEY (attachment_id) REFERENCES attachments(attachment_id) ON DELETE SET NULL,
    CONSTRAINT fk_fraud_cases_assigned_to FOREIGN KEY (assigned_to) REFERENCES customers(id) ON DELETE SET NULL
);

-- Indexes for fraud case queries
CREATE INDEX idx_fraud_cases_session_id ON fraud_cases(session_id);
CREATE INDEX idx_fraud_cases_status ON fraud_cases(status);
CREATE INDEX idx_fraud_cases_fraud_type ON fraud_cases(fraud_type);
CREATE INDEX idx_fraud_cases_risk_score ON fraud_cases(risk_score DESC);
CREATE INDEX idx_fraud_cases_created_at ON fraud_cases(created_at DESC);

-- Fraud Scores Cache: Real-time scoring for quick checks
CREATE TABLE fraud_score_cache (
    id                      BIGSERIAL PRIMARY KEY,
    entity_type             VARCHAR(20) NOT NULL CHECK (entity_type IN ('CUSTOMER', 'MESSAGE', 'ATTACHMENT', 'SESSION')),
    entity_id               UUID NOT NULL,
    score                   DECIMAL(5, 2) NOT NULL CHECK (score BETWEEN 0 AND 100),
    risk_level              VARCHAR(10) NOT NULL CHECK (risk_level IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    factors                 JSONB,
    expires_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at              TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_fraud_score_cache_entity UNIQUE (entity_type, entity_id)
);

-- Indexes for fraud score cache
CREATE INDEX idx_fraud_score_cache_entity ON fraud_score_cache(entity_type, entity_id);
CREATE INDEX idx_fraud_score_cache_expires_at ON fraud_score_cache(expires_at);
CREATE INDEX idx_fraud_score_cache_risk_level ON fraud_score_cache(risk_level);

-- Session-level audit log for compliance
CREATE TABLE chat_session_audit (
    id                      BIGSERIAL PRIMARY KEY,
    session_id              UUID NOT NULL,
    action                  VARCHAR(50) NOT NULL CHECK (action IN ('CREATE', 'UPDATE', 'MESSAGE', 'ATTACHMENT', 'CLOSE', 'ESCALATE', 'FRAUD_FLAG')),
    actor_id                BIGINT,
    actor_role              VARCHAR(20),
    details                 JSONB,
    created_at              TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_session_audit_session FOREIGN KEY (session_id) REFERENCES chat_sessions(session_id) ON DELETE CASCADE
);

CREATE INDEX idx_session_audit_session_id ON chat_session_audit(session_id);
CREATE INDEX idx_session_audit_created_at ON chat_session_audit(created_at DESC);
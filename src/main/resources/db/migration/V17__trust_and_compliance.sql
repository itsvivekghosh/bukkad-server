-- V17: Trust & compliance — GST invoice PDFs, delivery proof of handover,
--      ETA accuracy reporting support, review moderation query support.
--
-- Scope notes:
--  * order_invoices already exists (V13). This migration only adds the PDF
--    artifact + email audit columns; no invoice data is rewritten.
--  * reviews.moderation_status / reviews.owner_response already exist (V13).
--    Only the supporting index is added here so public reads can filter by
--    moderation state without a full scan.
--  * orders.estimated_delivery_at (promised) and orders.delivered_at (actual)
--    already exist (V1). The ETA accuracy metric is derived from those two
--    columns, so no new order columns are required — only an index.

-- ---------------------------------------------------------------------------
-- 1. GST invoice PDF artifacts and email delivery audit
-- ---------------------------------------------------------------------------
-- pdf_storage_key   : object-storage key of the rendered PDF (presigned on read)
-- pdf_generated_at  : when the PDF was rendered; NULL means "JSON invoice only"
-- emailed_at        : when the invoice email was accepted by the mail sender
-- email_recipient   : address the invoice was sent to (audit trail; the customer
--                     email may change later, so it is snapshotted here)
-- email_attempts    : number of send attempts, used to stop retry loops
ALTER TABLE order_invoices
    ADD COLUMN pdf_storage_key  VARCHAR(512) NULL,
    ADD COLUMN pdf_generated_at TIMESTAMP    NULL,
    ADD COLUMN emailed_at       TIMESTAMP    NULL,
    ADD COLUMN email_recipient  VARCHAR(255) NULL,
    ADD COLUMN email_attempts   INT          NOT NULL DEFAULT 0;

-- Lets the invoice mailer poll for "PDF ready but not yet emailed" rows.
ALTER TABLE order_invoices
    ADD INDEX idx_invoice_email_pending (emailed_at, pdf_generated_at);

-- ---------------------------------------------------------------------------
-- 2. Delivery proof of handover (OTP + photo)
-- ---------------------------------------------------------------------------
-- One row per order. Created when the rider requests an OTP (or uploads a
-- photo) and completed when the handover is verified. Kept in a separate table
-- rather than on `orders` so the hot orders row stays narrow and the OTP hash
-- can be dropped independently for data-retention purposes.
--
-- otp_code_hash : hash of the 6-digit OTP shown to the customer. The plaintext
--                 OTP is never stored.
-- proof_type    : OTP | PHOTO | OTP_AND_PHOTO | SKIPPED (contactless/absent)
-- status        : PENDING | VERIFIED | FAILED | SKIPPED
CREATE TABLE order_delivery_proofs (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id            BIGINT       NOT NULL,
    agent_id            BIGINT       NULL,
    proof_type          VARCHAR(20)  NOT NULL DEFAULT 'OTP',
    status              VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    otp_code_hash       VARCHAR(128) NULL,
    otp_issued_at       TIMESTAMP    NULL,
    otp_expires_at      TIMESTAMP    NULL,
    otp_attempts        INT          NOT NULL DEFAULT 0,
    verified_at         TIMESTAMP    NULL,
    photo_storage_key   VARCHAR(512) NULL,
    photo_uploaded_at   TIMESTAMP    NULL,
    recipient_name      VARCHAR(120) NULL,
    capture_latitude    DOUBLE       NULL,
    capture_longitude   DOUBLE       NULL,
    notes               TEXT         NULL,
    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_delivery_proof_order FOREIGN KEY (order_id) REFERENCES orders(id),
    CONSTRAINT fk_delivery_proof_agent FOREIGN KEY (agent_id) REFERENCES delivery_agents(id),
    UNIQUE KEY uk_delivery_proof_order (order_id),
    INDEX idx_delivery_proof_agent (agent_id, status),
    INDEX idx_delivery_proof_status (status, created_at)
);

-- ---------------------------------------------------------------------------
-- 3. ETA accuracy reporting support
-- ---------------------------------------------------------------------------
-- The ops dashboard compares estimated_delivery_at against delivered_at over a
-- recent window. This index keeps that aggregation off a full table scan.
ALTER TABLE orders
    ADD INDEX idx_order_eta_accuracy (delivered_at, estimated_delivery_at);

-- ---------------------------------------------------------------------------
-- 4. Review moderation query support
-- ---------------------------------------------------------------------------
-- Public restaurant review reads filter to APPROVED; the admin moderation
-- queue filters to PENDING ordered by age.
ALTER TABLE reviews
    ADD INDEX idx_review_restaurant_moderation (restaurant_id, moderation_status),
    ADD INDEX idx_review_moderation_queue (moderation_status, created_at);

-- ---------------------------------------------------------------------------
-- 5. Fraud detection counting support
-- ---------------------------------------------------------------------------
-- Every guarded endpoint (register, login, order create) runs two COUNT queries
-- on fraud_events in the request path, so these must be covering:
--
--   ... WHERE event_type = ? AND ip_address = ?          AND created_at > ?
--   ... WHERE event_type = ? AND device_fingerprint = ?  AND created_at > ?
--
-- Column order matters. Both equality predicates come first so they narrow the
-- range, and created_at comes last so the sliding window is an index range scan
-- rather than a filter over every row ever recorded for that source.
--
-- The pre-existing idx_fraud_type (event_type) and idx_fraud_customer
-- (customer_id) from V13 are left in place: idx_fraud_type is now a redundant
-- prefix of both new indexes, but dropping it is a separate cleanup and would
-- make this migration harder to reverse.
--
-- Names are referenced in the FraudEventRepository Javadoc; keep them in sync.
ALTER TABLE fraud_events
    ADD INDEX idx_fraud_ip_type_created (event_type, ip_address, created_at),
    ADD INDEX idx_fraud_fingerprint_type_created (event_type, device_fingerprint, created_at);

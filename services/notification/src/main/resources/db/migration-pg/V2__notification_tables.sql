CREATE TABLE notifications (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    channel     VARCHAR(20)   NOT NULL,
    recipient   VARCHAR(255)  NOT NULL,
    template    VARCHAR(100),
    subject     VARCHAR(255),
    body        TEXT,
    status      VARCHAR(20)   NOT NULL,
    provider_ref VARCHAR(100),
    error       VARCHAR(1000),
    created_at  TIMESTAMP(6) NOT NULL,
    updated_at  TIMESTAMP(6) NOT NULL
);

CREATE INDEX idx_notifications_channel_status ON notifications (channel, status);
CREATE INDEX idx_notifications_recipient ON notifications (recipient, created_at);
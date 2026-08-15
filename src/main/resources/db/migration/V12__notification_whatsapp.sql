-- WhatsApp notification preference channel

ALTER TABLE customer_notification_preferences
    ADD COLUMN whatsapp_enabled BOOLEAN NOT NULL DEFAULT TRUE;

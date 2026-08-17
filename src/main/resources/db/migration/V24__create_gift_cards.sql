-- V24: Create gift_cards table for GiftCard entity
CREATE TABLE gift_cards (
    id BIGINT NOT NULL AUTO_INCREMENT,
    code VARCHAR(20) NOT NULL UNIQUE,
    amount DOUBLE NOT NULL,
    balance DOUBLE NOT NULL,
    status VARCHAR(20) NOT NULL,
    purchased_by BIGINT NULL,
    recipient_email VARCHAR(100) NULL,
    recipient_name VARCHAR(100) NULL,
    message TEXT NULL,
    redeemed_by BIGINT NULL,
    redeemed_at DATETIME NULL,
    expires_at DATETIME NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE gift_cards
    ADD CONSTRAINT fk_gift_card_purchaser FOREIGN KEY (purchased_by) REFERENCES users(id) ON DELETE SET NULL,
    ADD CONSTRAINT fk_gift_card_redeemer FOREIGN KEY (redeemed_by) REFERENCES users(id) ON DELETE SET NULL;

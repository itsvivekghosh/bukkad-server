-- V23: Create user_referral_codes table for User.referralCodes element collection
CREATE TABLE user_referral_codes (
    user_id BIGINT NOT NULL,
    code VARCHAR(20) NOT NULL,
    PRIMARY KEY (user_id, code),
    CONSTRAINT fk_referral_code_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

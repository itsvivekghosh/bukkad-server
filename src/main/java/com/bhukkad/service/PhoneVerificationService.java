package com.bhukkad.service;

/**
 * Sends one-time passwords (OTP) for phone-number verification during
 * the phone-first registration flow.
 *
 * <p>Reuses the same OTP-generation, Redis hashing, and notification
 * infrastructure as {@link com.bhukkad.order.GuestCheckoutService} so that
 * registration, guest checkout, and future flows share a single OTP strategy.</p>
 */
public interface PhoneVerificationService {

    /**
     * Generates, stores (as a SHA-256 hash in Redis), and sends a 6-digit OTP
     * to the given phone number via the requested channel.
     *
     * @param phone   the recipient phone number (10 digits, Indian format)
     * @param channel "sms" or "whatsapp"
     * @throws com.bhukkad.exception.BusinessException if the phone is blank or
     *         the channel is unsupported
     */
    void sendOtp(String phone, String channel);

    /**
     * Validates the supplied code against the stored hash for the phone.
     * On success the OTP is consumed (deleted from Redis) so it cannot be reused.
     *
     * @return {@code true} if the OTP was valid and consumed
     * @throws com.bhukkad.exception.BusinessException if the OTP is missing,
     *         expired, or does not match
     */
    boolean verifyOtp(String phone, String code);

    /**
     * Resends a fresh OTP, invalidating any previous code for the same phone.
     */
    void resendOtp(String phone, String channel);
}

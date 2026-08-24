package com.bhukkad.order;

import com.bhukkad.exception.BusinessException;
import com.bhukkad.service.NotificationService;
import com.bhukkad.util.Constants;
import com.bhukkad.util.OTPGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Anonymous (guest) checkout support. Guests are identified by a device-scoped
 * id; a phone number is captured via OTP verification so the order can later be
 * linked to a real identity when the guest chooses to create an account.
 *
 * <p><strong>Cart keying note:</strong> database carts are customer-owned
 * ({@code carts.customer_id} NOT NULL), so a guest has no DB cart yet. This
 * service therefore maintains a device-scoped guest cart handle in Redis
 * (keyed {@code guest:cart:<deviceId>}) that the order-placement integration
 * resolves into a real cart once the guest identity is promoted (OTP-verified
 * phone + device binding).
 *
 * <p>OTP codes are stored as SHA-256 hashes (plaintext is never persisted) in
 * Redis with a TTL matching {@link Constants#OTP_EXPIRY_MINUTES}. A successful
 * verification mints a short-lived guest token (30 min TTL) that authorizes the
 * subsequent order-placement step.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GuestCheckoutService {

    private static final String GUEST_CART_KEY_PREFIX = "guest:cart:";
    private static final String GUEST_OTP_KEY_PREFIX = "guest:otp:";
    private static final String GUEST_TOKEN_KEY_PREFIX = "guest:token:";
    private static final Duration GUEST_CART_TTL = Duration.ofHours(24);
    private static final Duration GUEST_TOKEN_TTL = Duration.ofMinutes(30);

    private final StringRedisTemplate stringRedisTemplate;
    private final NotificationService notificationService;

    /**
     * Creates (or looks up) the device-scoped guest cart handle for the given
     * anonymous device id.
     *
     * @return a stable guest cart id for this device
     */
    public String beginGuestCheckout(String deviceId) {
        if (!StringUtils.hasText(deviceId)) {
            throw new BusinessException("deviceId is required");
        }
        String redisKey = GUEST_CART_KEY_PREFIX + deviceId;
        String existing = stringRedisTemplate.opsForValue().get(redisKey);
        if (StringUtils.hasText(existing)) {
            return existing;
        }
        String guestCartId = UUID.randomUUID().toString();
        stringRedisTemplate.opsForValue().set(redisKey, guestCartId, GUEST_CART_TTL);
        log.info("Guest checkout started | deviceId={} | guestCartId={}", deviceId, guestCartId);
        return guestCartId;
    }

    /**
     * Generates a 6-digit OTP for the given phone, stores a SHA-256 hash in
     * Redis with a TTL, and sends it via SMS (best-effort; an SMS failure never
     * prevents the OTP from being stored).
     */
    public String sendOtp(String phone) {
        if (!StringUtils.hasText(phone)) {
            throw new BusinessException("phone is required");
        }
        String code = OTPGenerator.generateOTP();
        stringRedisTemplate.opsForValue().set(
                GUEST_OTP_KEY_PREFIX + phone,
                hashOtp(code),
                Duration.ofMinutes(Constants.OTP_EXPIRY_MINUTES));
        try {
            notificationService.sendTestNotification("sms", phone,
                    "Your Bhukkad OTP is " + code + ". Valid for "
                            + Constants.OTP_EXPIRY_MINUTES + " minutes.");
        } catch (Exception ex) {
            log.warn("Failed to send guest OTP SMS | phone={} | error={}", phone, ex.getMessage());
        }
        return "otp sent";
    }

    /**
     * Validates the code against the stored OTP hash for the phone. On success
     * the OTP is consumed and a short-lived guest token is returned for the
     * order-placement step.
     */
    public String verifyOtp(String phone, String code) {
        if (!StringUtils.hasText(phone) || !StringUtils.hasText(code)) {
            throw new BusinessException("phone and code are required");
        }
        String redisKey = GUEST_OTP_KEY_PREFIX + phone;
        String storedHash = stringRedisTemplate.opsForValue().get(redisKey);
        if (!StringUtils.hasText(storedHash) || !storedHash.equals(hashOtp(code))) {
            throw new BusinessException("Invalid or expired OTP");
        }
        stringRedisTemplate.delete(redisKey);
        String guestToken = UUID.randomUUID().toString();
        stringRedisTemplate.opsForValue().set(GUEST_TOKEN_KEY_PREFIX + guestToken, phone, GUEST_TOKEN_TTL);
        log.info("Guest OTP verified | phone={}", phone);
        return guestToken;
    }

    /** SHA-256 hex digest of the OTP; the plaintext code is never stored. */
    static String hashOtp(String code) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(code.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}

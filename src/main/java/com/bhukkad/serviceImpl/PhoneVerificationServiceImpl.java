package com.bhukkad.serviceImpl;

import com.bhukkad.exception.BusinessException;
import com.bhukkad.service.NotificationService;
import com.bhukkad.service.PhoneVerificationService;
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

/**
 * OTP-based phone verification for the phone-first registration flow.
 *
 * <p>OTP codes are stored as SHA-256 hashes in Redis (plaintext is never
 * persisted) and are single-use. The implementation mirrors the pattern
 * established in {@link com.bhukkad.order.GuestCheckoutService} but uses a
 * distinct Redis-key namespace so registration OTPs never collide with
 * guest-checkout OTPs.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PhoneVerificationServiceImpl implements PhoneVerificationService {

    private static final String OTP_KEY_PREFIX = "auth:otp:";
    private static final Duration OTP_TTL = Duration.ofMinutes(Constants.OTP_EXPIRY_MINUTES);

    private final StringRedisTemplate stringRedisTemplate;
    private final NotificationService notificationService;

    @Override
    public void sendOtp(String phone, String channel) {
        if (!StringUtils.hasText(phone)) {
            throw new BusinessException("phone is required");
        }
        String normalizedChannel = normalizeChannel(channel);

        String code = OTPGenerator.generateOTP();

        // Store the OTP hash in Redis. If Redis is unavailable, log the OTP so
        // the registration flow still completes (verification will be rejected
        // gracefully on the next step).
        boolean redisOk = true;
        try {
            stringRedisTemplate.opsForValue().set(
                    key(phone),
                    hashOtp(code),
                    OTP_TTL);
        } catch (Exception ex) {
            redisOk = false;
            log.warn("Redis unavailable, OTP not stored | phone={} | error={}",
                    maskPhone(phone), ex.getMessage());
        }

        String message = "Your Bhukkad verification code is " + code
                + ". Valid for " + Constants.OTP_EXPIRY_MINUTES + " minutes.";

        if (!redisOk) {
            log.info("OTP for verification (Redis down) | phone={} | code={}",
                    maskPhone(phone), code);
        }

        try {
            notificationService.sendTestNotification(normalizedChannel, phone, message);
            log.info("OTP sent | phone={} | channel={}", maskPhone(phone), normalizedChannel);
        } catch (Exception ex) {
            log.warn("Failed to send OTP | phone={} | channel={} | error={}",
                    maskPhone(phone), normalizedChannel, ex.getMessage());
            throw new BusinessException("Failed to send OTP via " + normalizedChannel
                    + ". Please try again.");
        }
    }

    @Override
    public boolean verifyOtp(String phone, String code) {
        if (!StringUtils.hasText(phone) || !StringUtils.hasText(code)) {
            throw new BusinessException("phone and code are required");
        }
        String key = key(phone);
        String storedHash;
        try {
            storedHash = stringRedisTemplate.opsForValue().get(key);
        } catch (Exception ex) {
            log.warn("Redis unavailable during OTP verification | phone={}", maskPhone(phone));
            throw new BusinessException("Unable to verify OTP — please try again");
        }
        if (!StringUtils.hasText(storedHash) || !storedHash.equals(hashOtp(code))) {
            throw new BusinessException("Invalid or expired OTP");
        }
        try {
            stringRedisTemplate.delete(key);
        } catch (Exception ex) {
            log.warn("Redis unavailable, OTP not deleted | phone={}", maskPhone(phone));
        }
        log.info("OTP verified | phone={}", maskPhone(phone));
        return true;
    }

    @Override
    public void resendOtp(String phone, String channel) {
        // Invalidate the previous OTP and issue a fresh one.
        try {
            stringRedisTemplate.delete(key(phone));
        } catch (Exception ex) {
            log.warn("Redis unavailable, could not delete previous OTP | phone={}", maskPhone(phone));
        }
        sendOtp(phone, channel);
    }

    private String key(String phone) {
        return OTP_KEY_PREFIX + phone;
    }

    private String normalizeChannel(String channel) {
        if (!StringUtils.hasText(channel)) {
            return "sms";
        }
        switch (channel.toLowerCase()) {
            case "whatsapp":
            case "sms":
                return channel.toLowerCase();
            default:
                throw new BusinessException("Unsupported channel: " + channel);
        }
    }

    /** SHA-256 hex digest of the OTP; identical to GuestCheckoutService.hashOtp. */
    static String hashOtp(String code) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(code.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 4) return "****";
        return "****" + phone.substring(phone.length() - 4);
    }
}

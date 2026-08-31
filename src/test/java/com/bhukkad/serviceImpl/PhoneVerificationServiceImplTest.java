package com.bhukkad.serviceImpl;

import com.bhukkad.exception.BusinessException;
import com.bhukkad.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Tag;
import org.mockito.InOrder;

/**
 * Unit tests for PhoneVerificationServiceImpl.
 *
 * <p>Follows the same mocking pattern as {@link com.bhukkad.order.GuestCheckoutServiceTest},
 * verifying the OTP-hash-storage contract (plaintext is never persisted) and the
 * graceful handling of notification-sender failures.</p>
 */
@ExtendWith(MockitoExtension.class)
class PhoneVerificationServiceImplTest {

    @Mock private StringRedisTemplate stringRedisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;
    @Mock private NotificationService notificationService;

    private PhoneVerificationServiceImpl service;

    @BeforeEach
    void setUp() {
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        service = new PhoneVerificationServiceImpl(stringRedisTemplate, notificationService);
    }

    @Test
    void sendOtp_storesHashedOtpWithTtl_andSendsSmsByDefault() {
        service.sendOtp("9876543210", "sms");

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(valueOperations).set(keyCaptor.capture(), valueCaptor.capture(), ttlCaptor.capture());

        assertEquals("auth:otp:9876543210", keyCaptor.getValue());
        assertNotEquals("123456", valueCaptor.getValue(), "plaintext OTP must never be stored");
        assertEquals(64, valueCaptor.getValue().length(), "SHA-256 hex digest length");
        assertEquals(Duration.ofMinutes(10), ttlCaptor.getValue());

        verify(notificationService).sendTestNotification(eq("sms"), eq("9876543210"),
                contains("verification code"));
    }

    @Test
    void sendOtp_whatsappChannel_sendsViaWhatsApp() {
        service.sendOtp("9876543210", "whatsapp");

        verify(notificationService).sendTestNotification(eq("whatsapp"), eq("9876543210"),
                contains("verification code"));
    }

    @Test
    void sendOtp_smsFailure_throwsBusinessException() {
        doThrow(new RuntimeException("sms provider down"))
                .when(notificationService).sendTestNotification(eq("sms"), anyString(), anyString());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.sendOtp("9876543210", "sms"));
        assertTrue(ex.getMessage().contains("Failed to send OTP"));
        verify(valueOperations).set(eq("auth:otp:9876543210"), anyString(), any(Duration.class));
    }

    @Test
    void sendOtp_blankPhone_throws() {
        assertThrows(BusinessException.class, () -> service.sendOtp("", "sms"));
    }

    @Test
    void sendOtp_invalidChannel_throws() {
        assertThrows(BusinessException.class, () -> service.sendOtp("9876543210", "carrier_pigeon"));
    }

    @Test
    void sendOtp_nullChannel_defaultsToSms() {
        service.sendOtp("9876543210", null);
        verify(notificationService).sendTestNotification(eq("sms"), anyString(), anyString());
    }

    @Test
    void verifyOtp_validCode_consumesOtp() {
        when(valueOperations.get("auth:otp:9876543210"))
                .thenReturn(PhoneVerificationServiceImpl.hashOtp("123456"));

        boolean result = service.verifyOtp("9876543210", "123456");

        assertTrue(result);
        verify(stringRedisTemplate).delete("auth:otp:9876543210");
    }

    @Test
    void verifyOtp_wrongCode_throwsAndKeepsOtp() {
        when(valueOperations.get("auth:otp:9876543210"))
                .thenReturn(PhoneVerificationServiceImpl.hashOtp("000000"));

        assertThrows(BusinessException.class, () -> service.verifyOtp("9876543210", "123456"));
        verify(stringRedisTemplate, never()).delete(anyString());
    }

    @Test
    void verifyOtp_expiredOtp_throws() {
        when(valueOperations.get("auth:otp:9876543210")).thenReturn(null);

        assertThrows(BusinessException.class, () -> service.verifyOtp("9876543210", "123456"));
        verify(stringRedisTemplate, never()).delete(anyString());
    }

    @Test
    void verifyOtp_blankInputs_throws() {
        assertThrows(BusinessException.class, () -> service.verifyOtp("", "123456"));
        assertThrows(BusinessException.class, () -> service.verifyOtp("9876543210", null));
    }

    @Test
    void resendOtp_deletesPreviousOtp_thenSendsNew() {
        service.resendOtp("9876543210", "sms");

        // Verify delete was called first, then set was called
        InOrder inOrder = inOrder(stringRedisTemplate, valueOperations, notificationService);
        inOrder.verify(stringRedisTemplate).delete("auth:otp:9876543210");
        inOrder.verify(valueOperations).set(eq("auth:otp:9876543210"), anyString(), any(Duration.class));
        inOrder.verify(notificationService).sendTestNotification(eq("sms"), eq("9876543210"), anyString());
    }

    @Test
    void hashOtp_neverReturnsPlaintext() {
        String hash = PhoneVerificationServiceImpl.hashOtp("123456");
        assertNotEquals("123456", hash);
        assertEquals(64, hash.length()); // SHA-256 hex
    }

    // ------------------------------------------------------------------
    // Redis failure resilience
    // ------------------------------------------------------------------

    @Test
    void sendOtp_redisSetFailure_doesNotThrow() {
        when(stringRedisTemplate.opsForValue()).thenThrow(new RuntimeException("Redis down"));

        assertDoesNotThrow(() -> service.sendOtp("9876543210", "sms"));
        verify(notificationService).sendTestNotification(eq("sms"), eq("9876543210"), anyString());
    }

    @Test
    void resendOtp_redisDeleteFailure_doesNotThrow() {
        doThrow(new RuntimeException("Redis down")).when(stringRedisTemplate).delete(anyString());

        assertDoesNotThrow(() -> service.resendOtp("9876543210", "sms"));
        // sendOtp should still attempt to store and send
        verify(valueOperations).set(eq("auth:otp:9876543210"), anyString(), any(Duration.class));
    }

    @Test
    void verifyOtp_redisGetFailure_throwsBusinessException() {
        when(stringRedisTemplate.opsForValue()).thenThrow(new RuntimeException("Redis down"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.verifyOtp("9876543210", "123456"));
        assertEquals("Unable to verify OTP — please try again", ex.getMessage());
    }

    // ------------------------------------------------------------------
    // Additional branch / edge-case coverage
    // ------------------------------------------------------------------

    @Test
    void verifyOtp_redisDeleteFailure_doesNotThrow() {
        when(valueOperations.get("auth:otp:9876543210"))
                .thenReturn(PhoneVerificationServiceImpl.hashOtp("123456"));
        doThrow(new RuntimeException("Redis down")).when(stringRedisTemplate).delete(anyString());

        boolean result = service.verifyOtp("9876543210", "123456");

        assertTrue(result);
        // OTP was matched but deletion failed — should not block verification
        verify(stringRedisTemplate).delete("auth:otp:9876543210");
    }

    @Test
    void verifyOtp_whitespaceCode_throws() {
        assertThrows(BusinessException.class,
                () -> service.verifyOtp("9876543210", "   "));
    }

    @Test
    void sendOtp_redisFailureAndNotificationFailure_throwsBusinessException() {
        when(stringRedisTemplate.opsForValue()).thenThrow(new RuntimeException("Redis down"));
        doThrow(new RuntimeException("sms gateway down"))
                .when(notificationService).sendTestNotification(eq("sms"), anyString(), anyString());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.sendOtp("9876543210", "sms"));
        assertTrue(ex.getMessage().contains("Failed to send OTP"));
    }

    @Test
    void sendOtp_whitespacePhone_throws() {
        assertThrows(BusinessException.class, () -> service.sendOtp("  ", "sms"));
    }

    @Test
    void normalizeChannel_caseInsensitive() {
        service.sendOtp("9876543210", "SMS");
        verify(notificationService).sendTestNotification(eq("sms"), eq("9876543210"), anyString());
    }

    @Test
    void resendOtp_whatsappChannel_sendsViaWhatsApp() {
        service.resendOtp("9876543210", "whatsapp");

        verify(stringRedisTemplate).delete("auth:otp:9876543210");
        verify(notificationService).sendTestNotification(eq("whatsapp"), eq("9876543210"), anyString());
    }

    @Test
    void hashOtp_isDeterministic() {
        String hash1 = PhoneVerificationServiceImpl.hashOtp("123456");
        String hash2 = PhoneVerificationServiceImpl.hashOtp("123456");
        assertEquals(hash1, hash2);
    }

    @Test
    void hashOtp_differentCodesProduceDifferentHashes() {
        String hash1 = PhoneVerificationServiceImpl.hashOtp("123456");
        String hash2 = PhoneVerificationServiceImpl.hashOtp("654321");
        assertNotEquals(hash1, hash2);
    }

    @Test
    void sendOtp_otpCodeIncludedInMessage() {
        service.sendOtp("9876543210", "sms");

        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(notificationService).sendTestNotification(eq("sms"), eq("9876543210"),
                messageCaptor.capture());
        assertTrue(messageCaptor.getValue().contains("verification code"));
        assertTrue(messageCaptor.getValue().contains("minutes"));
    }

    @Test
    void resendOtp_redisDeleteFailure_doesNotBlockSending() {
        doThrow(new RuntimeException("Redis down")).when(stringRedisTemplate).delete(anyString());
        // sendOtp inside resendOtp should still call set and notify
        assertDoesNotThrow(() -> service.resendOtp("9876543210", "sms"));
        verify(valueOperations).set(eq("auth:otp:9876543210"), anyString(), any(Duration.class));
        verify(notificationService).sendTestNotification(eq("sms"), eq("9876543210"), anyString());
    }

    @Test
    void sendOtp_messageContainsOtpExpiryMinutes() {
        service.sendOtp("9876543210", "sms");

        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(notificationService).sendTestNotification(eq("sms"), eq("9876543210"),
                messageCaptor.capture());
        assertTrue(messageCaptor.getValue().contains(String.valueOf(com.bhukkad.util.Constants.OTP_EXPIRY_MINUTES)));
    }

    @Test
    void verifyOtp_nullCode_throws() {
        assertThrows(BusinessException.class,
                () -> service.verifyOtp("9876543210", null));
    }

    @Test
    void verifyOtp_nullPhone_throws() {
        assertThrows(BusinessException.class,
                () -> service.verifyOtp(null, "123456"));
    }

    @Test
    void sendOtp_nullPhone_throws() {
        assertThrows(BusinessException.class, () -> service.sendOtp(null, "sms"));
    }
}

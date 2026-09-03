package com.bhukkad.order;

import com.bhukkad.common.error.BusinessException;
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

@ExtendWith(MockitoExtension.class)
class GuestCheckoutServiceTest {

    @Mock private StringRedisTemplate stringRedisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;
    @Mock private NotificationService notificationService;

    private GuestCheckoutService service;

    @BeforeEach
    void setUp() {
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        service = new GuestCheckoutService(stringRedisTemplate, notificationService);
    }

    @Test
    void beginGuestCheckout_createsDeviceScopedCart() {
        when(valueOperations.get("guest:cart:dev-123")).thenReturn(null);

        String guestCartId = service.beginGuestCheckout("dev-123");

        assertNotNull(guestCartId);
        assertFalse(guestCartId.isBlank());
        verify(valueOperations).set(eq("guest:cart:dev-123"), eq(guestCartId), any(Duration.class));
    }

    @Test
    void beginGuestCheckout_reusesExistingCartForDevice() {
        when(valueOperations.get("guest:cart:dev-123")).thenReturn("existing-cart-1");

        String guestCartId = service.beginGuestCheckout("dev-123");

        assertEquals("existing-cart-1", guestCartId);
        verify(valueOperations, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void beginGuestCheckout_blankDeviceId_throws() {
        assertThrows(BusinessException.class, () -> service.beginGuestCheckout("   "));
    }

    @Test
    void sendOtp_storesHashedOtpWithTtl_andSendsSms() {
        service.sendOtp("+919876543210");

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(valueOperations).set(keyCaptor.capture(), valueCaptor.capture(), ttlCaptor.capture());

        assertEquals("guest:otp:+919876543210", keyCaptor.getValue());
        assertNotEquals("123456", valueCaptor.getValue(), "plaintext OTP must never be stored");
        assertEquals(64, valueCaptor.getValue().length(), "SHA-256 hex digest length");
        assertEquals(Duration.ofMinutes(10), ttlCaptor.getValue());

        verify(notificationService).sendTestNotification(eq("sms"), eq("+919876543210"),
                contains("OTP"));
    }

    @Test
    void sendOtp_smsFailure_isSwallowed() {
        doThrow(new RuntimeException("sms provider down"))
                .when(notificationService).sendTestNotification(eq("sms"), anyString(), anyString());

        String result = service.sendOtp("+919876543210");

        assertEquals("otp sent", result);
        verify(valueOperations).set(eq("guest:otp:+919876543210"), anyString(), any(Duration.class));
    }

    @Test
    void sendOtp_blankPhone_throws() {
        assertThrows(BusinessException.class, () -> service.sendOtp(""));
    }

    @Test
    void verifyOtp_validCode_consumesOtpAndMintsGuestToken() {
        when(valueOperations.get("guest:otp:+919876543210"))
                .thenReturn(GuestCheckoutService.hashOtp("123456"));

        String guestToken = service.verifyOtp("+919876543210", "123456");

        assertNotNull(guestToken);
        assertFalse(guestToken.isBlank());
        verify(stringRedisTemplate).delete("guest:otp:+919876543210");
        verify(valueOperations).set(eq("guest:token:" + guestToken), eq("+919876543210"),
                any(Duration.class));
    }

    @Test
    void verifyOtp_wrongCode_throwsAndKeepsOtp() {
        when(valueOperations.get("guest:otp:+919876543210"))
                .thenReturn(GuestCheckoutService.hashOtp("000000"));

        assertThrows(BusinessException.class, () -> service.verifyOtp("+919876543210", "123456"));
        verify(stringRedisTemplate, never()).delete(anyString());
    }

    @Test
    void verifyOtp_expiredOtp_throws() {
        when(valueOperations.get("guest:otp:+919876543210")).thenReturn(null);

        assertThrows(BusinessException.class, () -> service.verifyOtp("+919876543210", "123456"));
        verify(stringRedisTemplate, never()).delete(anyString());
    }

    @Test
    void verifyOtp_blankInputs_throws() {
        assertThrows(BusinessException.class, () -> service.verifyOtp("", "123456"));
        assertThrows(BusinessException.class, () -> service.verifyOtp("+919876543210", null));
    }
}

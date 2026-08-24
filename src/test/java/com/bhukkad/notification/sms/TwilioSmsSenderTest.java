package com.bhukkad.notification.sms;

import com.bhukkad.config.NotificationProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TwilioSmsSenderTest {

    @Mock
    private RestTemplate restTemplate;

    private NotificationProperties props;
    private TwilioSmsSender sender;

    @BeforeEach
    void setUp() {
        props = new NotificationProperties();
        props.getSms().getTwilio().setAccountSid("AC123");
        props.getSms().getTwilio().setAuthToken("tok");
        props.getSms().getTwilio().setFromNumber("+15551234567");
        sender = new TwilioSmsSender(props, restTemplate);
    }

    @Test void send_sendsViaTwilioApi() {
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                .thenReturn(null);

        sender.send("+911234567890", "Hello via Twilio");

        verify(restTemplate).postForEntity(
                org.mockito.ArgumentMatchers.startsWith("https://api.twilio.com/"),
                any(),
                eq(String.class));
    }

    @Test void send_emptyPhone_skips() {
        sender.send("", "test");
        verify(restTemplate, org.mockito.Mockito.never())
                .postForEntity(anyString(), any(), eq(String.class));
    }

    @Test void send_nullPhone_skips() {
        sender.send(null, "test");
        verify(restTemplate, org.mockito.Mockito.never())
                .postForEntity(anyString(), any(), eq(String.class));
    }

    @Test void send_missingCredentials_skips() {
        TwilioSmsSender withoutCreds = new TwilioSmsSender(new NotificationProperties(), restTemplate);
        withoutCreds.send("+911234567890", "no creds");
        verify(restTemplate, org.mockito.Mockito.never())
                .postForEntity(anyString(), any(), eq(String.class));
    }

    @Test void circuitBreakerFallback_doesNotThrow() {
        assertDoesNotThrow(() -> org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                sender, "smsUnavailable", "+911234567890", "msg", new RuntimeException("twilio down")));
    }
}
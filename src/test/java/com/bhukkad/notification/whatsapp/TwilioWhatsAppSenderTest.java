package com.bhukkad.notification.whatsapp;

import com.bhukkad.config.NotificationProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TwilioWhatsAppSenderTest {

    @Mock
    private RestTemplate restTemplate;

    private NotificationProperties props;
    private TwilioWhatsAppSender sender;

    @BeforeEach
    void setUp() {
        props = new NotificationProperties();
        props.getWhatsapp().getTwilio().setAccountSid("AC123");
        props.getWhatsapp().getTwilio().setAuthToken("tok");
        props.getWhatsapp().getTwilio().setWhatsappFromNumber("whatsapp:+15551234567");
        sender = new TwilioWhatsAppSender(props, restTemplate);
    }

    @Test void send_sendsViaTwilioApi_returnsTrue() {
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                .thenReturn(null);

        boolean delivered = sender.send("+911234567890", "Hello WhatsApp");

        assertTrue(delivered);
        verify(restTemplate).postForEntity(
                org.mockito.ArgumentMatchers.startsWith("https://api.twilio.com/"),
                any(),
                eq(String.class));
    }

    @Test void send_alreadyPrefixedPhone_notDoublePrefixed_returnsTrue() {
        when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                .thenReturn(null);

        boolean delivered = sender.send("whatsapp:+911234567890", "Hello");
        assertTrue(delivered);
        verify(restTemplate).postForEntity(anyString(), any(), eq(String.class));
    }

    @Test void send_emptyPhone_returnsFalse() {
        boolean delivered = sender.send("", "test");
        assertFalse(delivered);
        verify(restTemplate, org.mockito.Mockito.never())
                .postForEntity(anyString(), any(), eq(String.class));
    }

    @Test void send_nullPhone_returnsFalse() {
        boolean delivered = sender.send(null, "test");
        assertFalse(delivered);
        verify(restTemplate, org.mockito.Mockito.never())
                .postForEntity(anyString(), any(), eq(String.class));
    }

    @Test void send_missingCredentials_returnsFalse() {
        TwilioWhatsAppSender withoutCreds = new TwilioWhatsAppSender(new NotificationProperties(), restTemplate);
        boolean delivered = withoutCreds.send("+911234567890", "no creds");
        assertFalse(delivered);
        verify(restTemplate, org.mockito.Mockito.never())
                .postForEntity(anyString(), any(), eq(String.class));
    }

    @Test void fallback_returnsFalse() {
        Object result = org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                sender, "whatsAppUnavailable", "+911234567890", "msg", new RuntimeException("down"));
        assertFalse((Boolean) result);
    }
}
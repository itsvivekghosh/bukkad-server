package com.bhukkad.notification.whatsapp;

import com.bhukkad.config.NotificationProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestTemplate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

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

    @Test void send_sendsViaTwilioApi() {
        org.mockito.Mockito.when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                .thenReturn(null);

        sender.send("+911234567890", "Hello WhatsApp");

        verify(restTemplate).postForEntity(
                org.mockito.ArgumentMatchers.startsWith("https://api.twilio.com/"),
                any(),
                eq(String.class));
    }

    @Test void send_alreadyPrefixedPhone_notDoublePrefixed() {
        org.mockito.Mockito.when(restTemplate.postForEntity(anyString(), any(), eq(String.class)))
                .thenReturn(null);

        sender.send("whatsapp:+911234567890", "Hello");
        verify(restTemplate).postForEntity(anyString(), any(), eq(String.class));
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
        TwilioWhatsAppSender withoutCreds = new TwilioWhatsAppSender(new NotificationProperties(), restTemplate);
        withoutCreds.send("+911234567890", "no creds");
        verify(restTemplate, org.mockito.Mockito.never())
                .postForEntity(anyString(), any(), eq(String.class));
    }

    @Test void fallback_doesNotThrow() {
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                sender, "whatsAppUnavailable", "+911234567890", "msg", new RuntimeException("down"));
    }
}
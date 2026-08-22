package com.bhukkad.notification;

import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResilientEmailSenderTest {

    @Mock
    private JavaMailSender mailSender;

    private ResilientEmailSender emailSender;

    @BeforeEach
    void setUp() {
        emailSender = new ResilientEmailSender();
    }

    private SimpleMailMessage message() {
        SimpleMailMessage m = new SimpleMailMessage();
        m.setTo("a@example.com");
        m.setSubject("Hi");
        m.setText("Hello");
        return m;
    }

    @Test void send_withSender_sends() {
        ReflectionTestUtils.setField(emailSender, "mailSender", mailSender);
        emailSender.send(message());
        verify(mailSender).send(any(SimpleMailMessage.class));
    }

    @Test void send_withoutSender_throws() {
        assertThrows(IllegalStateException.class, () -> emailSender.send(message()));
    }

    @Test void emailUnavailable_fallback_doesNotThrow() {
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                emailSender, "emailUnavailable", message(), new RuntimeException("smtp down"));
    }

    @Test void sendWithAttachment_withSender_returnsTrue() throws Exception {
        MimeMessage mime = new MimeMessage((jakarta.mail.Session) null);
        ReflectionTestUtils.setField(emailSender, "mailSender", mailSender);
        when(mailSender.createMimeMessage()).thenReturn(mime);

        boolean sent = emailSender.sendWithAttachment(
                "from@example.com", "to@example.com", "Invoice", "Body",
                "inv.pdf", new byte[]{1, 2, 3}, "application/pdf");

        assertTrue(sent);
        verify(mailSender).send(any(MimeMessage.class));
    }

    @Test void sendWithAttachment_nullAttachment_stillSends() throws Exception {
        MimeMessage mime = new MimeMessage((jakarta.mail.Session) null);
        ReflectionTestUtils.setField(emailSender, "mailSender", mailSender);
        when(mailSender.createMimeMessage()).thenReturn(mime);

        boolean sent = emailSender.sendWithAttachment(
                "from@example.com", "to@example.com", "Invoice", "Body",
                "inv.pdf", null, "application/pdf");

        assertTrue(sent);
    }

    @Test void sendWithAttachment_withoutSender_throws() throws Exception {
        // The null-sender guard throws before any fallback can run (the
        // circuit-breaker fallback only applies to provider errors during an
        // actual send attempt).
        assertThrows(IllegalStateException.class, () -> emailSender.sendWithAttachment(
                "from@example.com", "to@example.com", "Invoice", "Body",
                "inv.pdf", new byte[]{1}, "application/pdf"));
    }

    @Test void attachmentUnavailable_fallback_returnsFalse() {
        boolean result = (boolean) org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                emailSender, "attachmentUnavailable",
                "f", "t", "s", "b", "a", new byte[]{1}, "pdf", new RuntimeException("down"));
        assertFalse(result);
    }
}
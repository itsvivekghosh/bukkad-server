package com.bhukkad.notification.infrastructure.client;

import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Plain-unit tests for the resilient sender (the @CircuitBreaker/@Bulkhead
 * advice is AOP wiring exercised in context tests; here we pin the delegate
 * logic: guarded sender, multipart assembly, masking and the fallback bodies).
 */
@ExtendWith(MockitoExtension.class)
class ResilientEmailSenderTest {

    @Mock private JavaMailSender mailSender;

    private ResilientEmailSender sender;

    @BeforeEach
    void wire() throws Exception {
        sender = new ResilientEmailSender();
        Field field = ResilientEmailSender.class.getDeclaredField("mailSender");
        field.setAccessible(true);
        field.set(sender, mailSender);
    }

    private void withoutSender() throws Exception {
        Field field = ResilientEmailSender.class.getDeclaredField("mailSender");
        field.setAccessible(true);
        field.set(sender, null);
    }

    @Test
    void send_delegatesToMailSender() {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo("diner@example.com");
        message.setSubject("Order confirmed");

        sender.send(message);

        verify(mailSender).send(message);
    }

    @Test
    void send_withoutConfiguredSender_throws() throws Exception {
        withoutSender();
        assertThatThrownBy(() -> sender.send(new SimpleMailMessage()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("JavaMailSender is not configured");
    }

    @Test
    void sendWithAttachment_assemblesMultipartAndSends() throws Exception {
        MimeMessage mime = new MimeMessage((jakarta.mail.Session) null);
        when(mailSender.createMimeMessage()).thenReturn(mime);

        boolean sent = sender.sendWithAttachment("ops@bhukkad.app", "diner@example.com",
                "GST invoice", "Your invoice is attached.", "INV-42.pdf",
                new byte[]{1, 2, 3}, "application/pdf");

        assertThat(sent).isTrue();
        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        assertThat(captor.getValue()).isSameAs(mime);
    }

    @Test
    void sendWithAttachment_skipsEmptyAttachments() throws Exception {
        MimeMessage mime = new MimeMessage((jakarta.mail.Session) null);
        when(mailSender.createMimeMessage()).thenReturn(mime);

        assertThat(sender.sendWithAttachment("ops@bhukkad.app", "diner@example.com",
                "GST invoice", "body", "none.pdf", new byte[0], "application/pdf")).isTrue();

        assertThat(sender.sendWithAttachment("ops@bhukkad.app", "diner@example.com",
                "GST invoice", "body", "none.pdf", null, "application/pdf")).isTrue();

        verify(mailSender, org.mockito.Mockito.times(2)).send(any(MimeMessage.class));
    }

    @Test
    void sendWithAttachment_withoutConfiguredSender_throws() throws Exception {
        withoutSender();
        assertThatThrownBy(() -> sender.sendWithAttachment("f@x.io", "t@x.io",
                "s", "b", "a.pdf", new byte[]{9}, "application/pdf"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void maskTo_handlesNullAndMultipleRecipients() {
        assertThat(ResilientEmailSender.maskTo(null)).isEqualTo("unknown");
        assertThat(ResilientEmailSender.maskTo(new String[]{"secret-dude@example.com", "a@b.io"}))
                .doesNotContain("secret-dude")
                .contains(",");
    }

    @Test
    void emailUnavailable_fallbackLogsAndDoesNotThrow() {
        SimpleMailMessage message = new SimpleMailMessage(); // no recipients → "unknown"
        message.setSubject("Invoice");

        sender.emailUnavailable(message, new MailException("smtp down") {
        });

        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    void emailUnavailable_masksRecipients() {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo("very-secret-guest@example.com");
        message.setSubject("hi");

        sender.emailUnavailable(message, new IllegalStateException("boom"));
    }

    @Test
    void attachmentUnavailable_returnsFalse() {
        boolean sent = sender.attachmentUnavailable("from@bhukkad.app", "to@diner.io",
                "GST invoice", "body", "INV.pdf", new byte[]{1}, "application/pdf",
                new IllegalStateException("breaker open"));

        assertThat(sent).isFalse();
    }
}

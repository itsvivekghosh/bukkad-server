package com.bhukkad.notification;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import jakarta.mail.internet.MimeMessage;

/**
 * Sends outbound email behind a circuit breaker so that a failing or slow SMTP
 * provider degrades notifications instead of stalling business transactions.
 *
 * <p>{@code JavaMailSender} is injected with {@code required = false} because
 * mail is disabled by default ({@code app.notification.email.enabled=false}) and
 * no sender bean may be present. Callers are expected to check their own
 * enablement flags first; if they do reach this component without a configured
 * sender, the resulting exception trips the breaker and is swallowed by the
 * fallback rather than propagating to the caller.
 *
 * <p>Two shapes are supported:
 * <ul>
 *   <li>{@link #send(SimpleMailMessage)} for plain text notifications.</li>
 *   <li>{@link #sendWithAttachment(String, String, String, String, String, byte[], String)}
 *       for multipart mail such as GST invoice PDFs.</li>
 * </ul>
 * Each has its own fallback so a breaker trip on one shape is logged with the
 * context relevant to it.
 *
 * <p>Both methods are fire-and-forget by contract: they return normally when the
 * message could not be delivered. Delivery accounting (attempt counts, sent
 * timestamps) is the caller's responsibility, which is why
 * {@link #sendWithAttachment} reports success as a boolean.
 */
@Slf4j
@Component
public class ResilientEmailSender {

    @Autowired(required = false)
    private JavaMailSender mailSender;

    /**
     * Sends a plain-text email.
     *
     * @param message fully populated simple mail message
     */
    @CircuitBreaker(name = "notificationEmail", fallbackMethod = "emailUnavailable")
    public void send(SimpleMailMessage message) {
        if (mailSender == null) {
            throw new IllegalStateException("JavaMailSender is not configured");
        }
        mailSender.send(message);
    }

    /**
     * Sends a multipart email carrying a single binary attachment.
     *
     * <p>Used for GST invoice delivery, where the customer must receive the tax
     * invoice as a PDF file rather than a link.
     *
     * @param from           envelope sender address
     * @param to             recipient address
     * @param subject        message subject
     * @param body           plain-text body
     * @param attachmentName file name shown to the recipient, e.g. {@code INV-2026-00000042.pdf}
     * @param attachment     attachment bytes
     * @param contentType    MIME type of the attachment, e.g. {@code application/pdf}
     * @return {@code true} when the message was handed to the mail provider,
     *         {@code false} when it was dropped (breaker open, provider error,
     *         or no sender configured)
     */
    @CircuitBreaker(name = "notificationEmail", fallbackMethod = "attachmentUnavailable")
    public boolean sendWithAttachment(String from,
                                      String to,
                                      String subject,
                                      String body,
                                      String attachmentName,
                                      byte[] attachment,
                                      String contentType) throws Exception {
        if (mailSender == null) {
            throw new IllegalStateException("JavaMailSender is not configured");
        }

        MimeMessage mimeMessage = mailSender.createMimeMessage();
        // multipart = true: required for attachments.
        MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");
        helper.setFrom(from);
        helper.setTo(to);
        helper.setSubject(subject);
        helper.setText(body, false);
        if (attachment != null && attachment.length > 0) {
            helper.addAttachment(attachmentName,
                    new org.springframework.core.io.ByteArrayResource(attachment),
                    contentType);
        }

        mailSender.send(mimeMessage);
        log.info("EMAIL_ATTACHMENT_SENT | to={} | subject={} | attachment={} | bytes={}",
                to, subject, attachmentName, attachment != null ? attachment.length : 0);
        return true;
    }

    @SuppressWarnings("unused")
    private void emailUnavailable(SimpleMailMessage message, Throwable cause) {
        log.warn("EMAIL_CIRCUIT_OPEN | to={} | subject={} | error={}",
                message.getTo(), message.getSubject(), cause.getMessage());
    }

    @SuppressWarnings("unused")
    private boolean attachmentUnavailable(String from,
                                          String to,
                                          String subject,
                                          String body,
                                          String attachmentName,
                                          byte[] attachment,
                                          String contentType,
                                          Throwable cause) {
        log.warn("EMAIL_ATTACHMENT_FAILED | to={} | subject={} | attachment={} | error={}",
                to, subject, attachmentName, cause.getMessage());
        return false;
    }
}

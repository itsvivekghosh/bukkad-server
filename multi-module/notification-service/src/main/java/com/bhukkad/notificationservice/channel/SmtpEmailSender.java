package com.bhukkad.notificationservice.channel;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * SMTP email sender behind a circuit breaker so a failing or slow mail
 * provider degrades notifications instead of stalling the consumer.
 *
 * <p>Activated with {@code app.notification.email.enabled=true}. {@code
 * JavaMailSender} is injected with {@code required = false} because no sender
 * bean exists until the mail starter is on the classpath; the fallback logs
 * the undeliverable message instead of throwing to the caller.</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(value = "app.notification.email.enabled", havingValue = "true")
public class SmtpEmailSender implements EmailSender {

    @Autowired(required = false)
    private JavaMailSender mailSender;

    @Override
    @CircuitBreaker(name = "notificationEmail", fallbackMethod = "emailUnavailable")
    public void send(String to, String subject, String body) {
        if (mailSender == null) {
            throw new IllegalStateException("JavaMailSender is not configured");
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(to);
        message.setSubject(subject);
        message.setText(body);
        mailSender.send(message);
    }

    @SuppressWarnings("unused")
    private void emailUnavailable(String to, String subject, String body, Throwable t) {
        log.warn("EMAIL_UNAVAILABLE | to={} | subject={} | error={}", to, subject, t.getMessage());
    }
}

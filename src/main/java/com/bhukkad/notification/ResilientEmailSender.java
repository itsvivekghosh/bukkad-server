package com.bhukkad.notification;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class ResilientEmailSender {

    @Autowired(required = false)
    private JavaMailSender mailSender;

    @CircuitBreaker(name = "notificationEmail", fallbackMethod = "emailUnavailable")
    public void send(SimpleMailMessage message) {
        if (mailSender == null) {
            throw new IllegalStateException("JavaMailSender is not configured");
        }
        mailSender.send(message);
    }

    @SuppressWarnings("unused")
    private void emailUnavailable(SimpleMailMessage message, Throwable cause) {
        log.warn("EMAIL_CIRCUIT_OPEN | to={} | subject={} | error={}",
                message.getTo(), message.getSubject(), cause.getMessage());
    }
}

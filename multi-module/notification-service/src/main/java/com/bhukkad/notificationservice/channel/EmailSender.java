package com.bhukkad.notificationservice.channel;

/**
 * Email channel. Implementations either delegate to SMTP via
 * {@code JavaMailSender} or log the message when mail is disabled.
 */
public interface EmailSender {

    /**
     * Sends a plain-text email.
     *
     * @param to      recipient address
     * @param subject message subject
     * @param body    message body
     */
    void send(String to, String subject, String body);
}

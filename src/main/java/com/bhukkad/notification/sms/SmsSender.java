package com.bhukkad.notification.sms;

/**
 * Sends an SMS message.
 *
 * @return {@code true} when the message was delivered (or deliberately
 *         simulated by a log-backed provider); {@code false} when delivery
 *         failed and the caller must treat it as not sent.
 */
public interface SmsSender {
    boolean send(String phoneNumber, String body);
}

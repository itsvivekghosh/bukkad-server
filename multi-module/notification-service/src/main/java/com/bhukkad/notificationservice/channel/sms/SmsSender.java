package com.bhukkad.notificationservice.channel.sms;

/**
 * SMS channel. Implementations route to a concrete provider (Twilio) or to the
 * log in non-production environments.
 */
public interface SmsSender {

    /**
     * Sends an SMS message.
     *
     * @param phoneNumber recipient in E.164 format
     * @param body        message body
     */
    void send(String phoneNumber, String body);
}

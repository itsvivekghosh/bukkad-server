package com.bhukkad.notification.sms;

public interface SmsSender {
    void send(String phoneNumber, String body);
}

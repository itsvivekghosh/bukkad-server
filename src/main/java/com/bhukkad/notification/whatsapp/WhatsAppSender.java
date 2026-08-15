package com.bhukkad.notification.whatsapp;

public interface WhatsAppSender {
    void send(String phoneNumber, String body);
}

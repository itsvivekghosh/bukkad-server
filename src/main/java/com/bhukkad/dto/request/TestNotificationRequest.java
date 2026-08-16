package com.bhukkad.dto.request;

import lombok.Data;

@Data
public class TestNotificationRequest {
    /** email | sms | whatsapp */
    private String channel;
    private String recipient;
    private String message;
}

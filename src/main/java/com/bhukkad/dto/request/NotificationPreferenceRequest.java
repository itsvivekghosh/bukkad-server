package com.bhukkad.dto.request;

import lombok.Data;

@Data
public class NotificationPreferenceRequest {
    private Boolean emailEnabled;
    private Boolean smsEnabled;
    private Boolean pushEnabled;
    private Boolean whatsappEnabled;
    private Boolean orderUpdatesEnabled;
    private Boolean promotionsEnabled;
}

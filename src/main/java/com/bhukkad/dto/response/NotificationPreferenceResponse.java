package com.bhukkad.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class NotificationPreferenceResponse {
    private Boolean emailEnabled;
    private Boolean smsEnabled;
    private Boolean pushEnabled;
    private Boolean whatsappEnabled;
    private Boolean orderUpdatesEnabled;
    private Boolean promotionsEnabled;
}

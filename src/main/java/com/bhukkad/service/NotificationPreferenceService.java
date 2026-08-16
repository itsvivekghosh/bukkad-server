package com.bhukkad.service;

import com.bhukkad.dto.request.NotificationPreferenceRequest;
import com.bhukkad.dto.response.NotificationPreferenceResponse;

public interface NotificationPreferenceService {

    NotificationPreferenceResponse getPreferences(Long customerId);

    NotificationPreferenceResponse updatePreferences(Long customerId, NotificationPreferenceRequest request);

    boolean isOrderUpdatesEnabled(Long customerId);

    boolean isEmailEnabled(Long customerId);

    boolean isSmsEnabled(Long customerId);

    boolean isPushEnabled(Long customerId);

    boolean isWhatsappEnabled(Long customerId);
}

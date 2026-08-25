package com.bhukkad.notificationservice.preference;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Service for notification-preference read/update and channel enablement
 * checks used by the dispatch path. Backed by an in-memory store until the
 * JPA repository on the {@code bhukkad_notifications} schema is wired in.
 */
@Service
@RequiredArgsConstructor
public class NotificationPreferenceService {

    private final CustomerNotificationPreference.Store store;

    public Map<String, Boolean> getPreferences(Long customerId) {
        return store.getOrCreate(customerId).asMap();
    }

    public Map<String, Boolean> updatePreferences(Long customerId, Map<String, Boolean> updates) {
        CustomerNotificationPreference pref = store.getOrCreate(customerId);
        pref.apply(updates);
        return pref.asMap();
    }

    public boolean isEmailEnabled(Long customerId) {
        return customerId == null || store.getOrCreate(customerId).isEmailEnabled();
    }

    public boolean isSmsEnabled(Long customerId) {
        return customerId == null || store.getOrCreate(customerId).isSmsEnabled();
    }

    public boolean isPushEnabled(Long customerId) {
        return customerId == null || store.getOrCreate(customerId).isPushEnabled();
    }

    public boolean isWhatsappEnabled(Long customerId) {
        return customerId == null || store.getOrCreate(customerId).isWhatsappEnabled();
    }
}

package com.bhukkad.notificationservice;

import com.bhukkad.notificationservice.preference.NotificationPreferenceService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotificationPreferenceServiceTest {

    private final NotificationPreferenceService service = new NotificationPreferenceService(new com.bhukkad.notificationservice.preference.CustomerNotificationPreference.Store());

    @Test
    void defaultsAllChannelsEnabled() {
        Map<String, Boolean> prefs = service.getPreferences(1L);

        assertTrue(prefs.get("emailEnabled"));
        assertTrue(prefs.get("smsEnabled"));
        assertTrue(prefs.get("pushEnabled"));
        assertTrue(prefs.get("whatsappEnabled"));
    }

    @Test
    void updateDisablesChannel() {
        service.updatePreferences(1L, Map.of("emailEnabled", false));

        assertFalse(service.isEmailEnabled(1L));
        assertTrue(service.isSmsEnabled(1L));
    }

    @Test
    void preferencesArePerCustomer() {
        service.updatePreferences(1L, Map.of("smsEnabled", false));

        assertFalse(service.isSmsEnabled(1L));
        assertTrue(service.isSmsEnabled(2L));
    }

    @Test
    void nullCustomerIdMeansEnabled() {
        assertTrue(service.isEmailEnabled(null));
        assertTrue(service.isPushEnabled(null));
    }
}

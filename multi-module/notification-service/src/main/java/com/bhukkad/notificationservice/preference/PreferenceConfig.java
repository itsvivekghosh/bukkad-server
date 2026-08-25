package com.bhukkad.notificationservice.preference;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Exposes the in-memory preference store as a Spring bean.
 */
@Configuration
public class PreferenceConfig {

    @Bean
    public CustomerNotificationPreference.Store preferenceStore() {
        return new CustomerNotificationPreference.Store();
    }
}

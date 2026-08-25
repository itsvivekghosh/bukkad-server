package com.bhukkad.notificationservice.preference;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lightweight notification preference holder, owned exclusively by the
 * notification-service. The monolith's {@code customer_notification_preferences}
 * table is the source of truth until migration; this model keeps the extracted
 * service self-contained (no cross-service entity/table dependency).
 *
 * <p>This in-memory repository is a placeholder for the JPA repository backed
 * by the {@code bhukkad_notifications} schema (V54). Swap implementations
 * without changing the service contract.</p>
 */
public class CustomerNotificationPreference {

    private final Long customerId;
    private boolean emailEnabled = true;
    private boolean smsEnabled = true;
    private boolean pushEnabled = true;
    private boolean whatsappEnabled = true;
    private boolean orderUpdatesEnabled = true;
    private boolean promotionsEnabled = true;

    public CustomerNotificationPreference(Long customerId) {
        this.customerId = customerId;
    }

    public Long getCustomerId() {
        return customerId;
    }

    public boolean isEmailEnabled() {
        return emailEnabled;
    }

    public void setEmailEnabled(boolean emailEnabled) {
        this.emailEnabled = emailEnabled;
    }

    public boolean isSmsEnabled() {
        return smsEnabled;
    }

    public void setSmsEnabled(boolean smsEnabled) {
        this.smsEnabled = smsEnabled;
    }

    public boolean isPushEnabled() {
        return pushEnabled;
    }

    public void setPushEnabled(boolean pushEnabled) {
        this.pushEnabled = pushEnabled;
    }

    public boolean isWhatsappEnabled() {
        return whatsappEnabled;
    }

    public void setWhatsappEnabled(boolean whatsappEnabled) {
        this.whatsappEnabled = whatsappEnabled;
    }

    public boolean isOrderUpdatesEnabled() {
        return orderUpdatesEnabled;
    }

    public void setOrderUpdatesEnabled(boolean orderUpdatesEnabled) {
        this.orderUpdatesEnabled = orderUpdatesEnabled;
    }

    public boolean isPromotionsEnabled() {
        return promotionsEnabled;
    }

    public void setPromotionsEnabled(boolean promotionsEnabled) {
        this.promotionsEnabled = promotionsEnabled;
    }

    public Map<String, Boolean> asMap() {
        return Map.of(
                "emailEnabled", emailEnabled,
                "smsEnabled", smsEnabled,
                "pushEnabled", pushEnabled,
                "whatsappEnabled", whatsappEnabled,
                "orderUpdatesEnabled", orderUpdatesEnabled,
                "promotionsEnabled", promotionsEnabled
        );
    }

    public void apply(Map<String, Boolean> updates) {
        if (updates.containsKey("emailEnabled")) {
            setEmailEnabled(updates.get("emailEnabled"));
        }
        if (updates.containsKey("smsEnabled")) {
            setSmsEnabled(updates.get("smsEnabled"));
        }
        if (updates.containsKey("pushEnabled")) {
            setPushEnabled(updates.get("pushEnabled"));
        }
        if (updates.containsKey("whatsappEnabled")) {
            setWhatsappEnabled(updates.get("whatsappEnabled"));
        }
        if (updates.containsKey("orderUpdatesEnabled")) {
            setOrderUpdatesEnabled(updates.get("orderUpdatesEnabled"));
        }
        if (updates.containsKey("promotionsEnabled")) {
            setPromotionsEnabled(updates.get("promotionsEnabled"));
        }
    }

    /** In-memory store keyed by customer id. */
    public static class Store {
        private final Map<Long, CustomerNotificationPreference> byCustomer = new ConcurrentHashMap<>();

        public CustomerNotificationPreference getOrCreate(Long customerId) {
            return byCustomer.computeIfAbsent(customerId, CustomerNotificationPreference::new);
        }
    }
}

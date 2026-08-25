package com.bhukkad.notificationservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point of the extracted notification-service.
 *
 * <p>Owns the NOTIFICATION domain: email/push/SMS/WhatsApp delivery, delivery
 * preferences and notification history. It is event-driven: order/payment
 * services publish {@code NOTIFICATION_REQUESTED} events via their local
 * transactional outbox; this service consumes them and dispatches over the
 * configured channels. It exposes a small REST surface for preference
 * management.</p>
 *
 * <p>Strangler Fig: the monolith continues to dispatch notifications
 * synchronously until traffic is migrated to this service; both can run
 * side-by-side without data conflicts because the service owns its preference
 * model independently.</p>
 */
@SpringBootApplication
public class NotificationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationServiceApplication.class, args);
    }
}

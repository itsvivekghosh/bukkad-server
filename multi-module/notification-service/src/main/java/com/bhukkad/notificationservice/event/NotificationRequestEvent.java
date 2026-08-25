package com.bhukkad.notificationservice.event;

/**
 * Contract for a notification dispatch request, carried as the JSON payload of
 * a {@code NOTIFICATION_REQUESTED} platform event.
 *
 * <p>The payload is self-contained: the notification-service must never query
 * another service's database to render a message. Producers (order, payment,
 * auth) resolve recipient + content at publish time and include every field
 * needed here. {@code eventId} is the idempotency key: a consumer must not
 * dispatch the same notification twice even if Kafka redelivers the event.</p>
 *
 * @param eventId    unique notification id (deduplication key)
 * @param orderId    order context, may be null for non-order notifications
 * @param userId     target user id
 * @param channel    EMAIL | PUSH | SMS | WHATSAPP
 * @param recipient  email address / phone / FCM token according to channel
 * @param subject    short subject/title, may be null for SMS/WhatsApp
 * @param body       message body
 * @param source     producing service, e.g. "order-service" (diagnostics)
 */
public record NotificationRequestEvent(
        String eventId,
        Long orderId,
        Long userId,
        String channel,
        String recipient,
        String subject,
        String body,
        String source
) {

    public static final String EVENT_TYPE = "NOTIFICATION_REQUESTED";
    public static final String AGGREGATE_TYPE = "NOTIFICATION";
}

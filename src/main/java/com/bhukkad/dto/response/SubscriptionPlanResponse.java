package com.bhukkad.dto.response;

import com.bhukkad.entity.SubscriptionPlan;
import com.bhukkad.entity.SubscriptionDelivery;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * Response view of a subscription meal plan, including its recent deliveries.
 */
public record SubscriptionPlanResponse(
        Long id,
        Long restaurantId,
        String title,
        SubscriptionPlan.Weekday weekday,
        LocalTime deliveryTime,
        Long deliveryAddressId,
        String paymentMethod,
        SubscriptionPlan.SubscriptionStatus status,
        LocalDate startDate,
        LocalDate nextDeliveryDate,
        List<Delivery> deliveries) {

    public record Delivery(
            Long id,
            Long orderId,
            LocalDate scheduledDate,
            SubscriptionDelivery.DeliveryStatus status) {

        public static Delivery from(SubscriptionDelivery d) {
            return new Delivery(d.getId(), d.getOrderId(), d.getScheduledDate(), d.getStatus());
        }
    }

    public static SubscriptionPlanResponse from(SubscriptionPlan plan) {
        List<Delivery> deliveries = plan.getDeliveries() == null
                ? List.of()
                : plan.getDeliveries().stream()
                        .sorted((a, b) -> b.getScheduledDate().compareTo(a.getScheduledDate()))
                        .map(Delivery::from)
                        .toList();
        return new SubscriptionPlanResponse(
                plan.getId(),
                plan.getRestaurantId(),
                plan.getTitle(),
                plan.getWeekday(),
                plan.getDeliveryTime(),
                plan.getDeliveryAddressId(),
                plan.getPaymentMethod(),
                plan.getStatus(),
                plan.getStartDate(),
                plan.getNextDeliveryDate(),
                deliveries);
    }
}

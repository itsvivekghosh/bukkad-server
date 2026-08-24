package com.bhukkad.dto.request;

import com.bhukkad.entity.SubscriptionPlan;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * Request body for creating a weekly subscription meal plan.
 */
public record SubscriptionPlanRequest(
        @NotNull(message = "Restaurant ID is required") Long restaurantId,

        @Size(max = 100, message = "Title must be at most 100 characters") String title,

        @NotBlank(message = "Weekday is required") String weekday,

        @NotNull(message = "Delivery time is required") LocalTime deliveryTime,

        @NotNull(message = "Delivery address ID is required") Long deliveryAddressId,

        @NotBlank(message = "Payment method is required") String paymentMethod,

        @NotNull(message = "Start date is required") LocalDate startDate,

        @NotNull(message = "At least one menu item is required")
        @Size(min = 1, message = "At least one menu item is required")
        List<Item> items) {

    /**
     * Snapshot of a single menu item included in the weekly plan.
     */
    public record Item(
            @NotNull(message = "Menu item ID is required") Long menuItemId,
            @NotNull(message = "Quantity is required") Integer quantity) {

        public Item {
            if (quantity != null && quantity <= 0) {
                throw new IllegalArgumentException("Quantity must be positive");
            }
        }
    }

    public SubscriptionPlan.Weekday weekdayEnum() {
        try {
            return SubscriptionPlan.Weekday.valueOf(weekday.trim().toUpperCase());
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new IllegalArgumentException("Invalid weekday: " + weekday
                    + " (expected MON/TUE/WED/THU/FRI/SAT/SUN)");
        }
    }
}

package com.bhukkad.order.domain.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A weekly recurring meal plan subscribed by a customer. The scheduler
 * materialises each due delivery (per {@code weekday} / {@code nextDeliveryDate})
 * into a real {@link Order} using the snapshot in {@code itemsJson}.
 */
@Entity
@Table(name = "subscription_plans", indexes = {
        @Index(name = "idx_sub_user", columnList = "userId"),
        @Index(name = "idx_sub_status", columnList = "status")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude = {"deliveries"})
@EqualsAndHashCode(exclude = {"deliveries"})
public class SubscriptionPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "restaurant_id", nullable = false)
    private Long restaurantId;

    @Column(length = 100)
    private String title;

    /** JSON snapshot of {@code [{"menuItemId":1,"quantity":2}, ...]} captured at subscribe time. */
    @Column(name = "items_json", columnDefinition = "TEXT")
    private String itemsJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Weekday weekday;

    @Column(name = "delivery_time", nullable = false)
    private LocalTime deliveryTime;

    @Column(name = "delivery_address_id", nullable = false)
    private Long deliveryAddressId;

    @Column(name = "payment_method", nullable = false, length = 30)
    private String paymentMethod;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SubscriptionStatus status = SubscriptionStatus.ACTIVE;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "next_delivery_date")
    private LocalDate nextDeliveryDate;

    @Column(name = "created_at", nullable = false, updatable = false)
    private java.time.LocalDateTime createdAt;

    @OneToMany(mappedBy = "plan", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<SubscriptionDelivery> deliveries = new ArrayList<>();

    public enum Weekday {
        MON, TUE, WED, THU, FRI, SAT, SUN
    }

    public enum SubscriptionStatus {
        ACTIVE, PAUSED, CANCELLED
    }
}

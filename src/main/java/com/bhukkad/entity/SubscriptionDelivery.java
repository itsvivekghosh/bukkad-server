package com.bhukkad.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDate;

/**
 * A single scheduled delivery instance of a {@link SubscriptionPlan}. The
 * scheduler creates a PENDING row per due date and flips it to PLACED (with the
 * materialised {@link Order}) or FAILED. {@code skipNextDelivery} writes a
 * SKIPPED row to advance the plan without placing an order.
 */
@Entity
@Table(name = "subscription_deliveries", indexes = {
        @Index(name = "idx_sub_del_plan", columnList = "subscription_plan_id"),
        @Index(name = "idx_sub_del_status", columnList = "status")
}, uniqueConstraints = {
        @UniqueConstraint(name = "uq_sub_date", columnNames = {"subscription_plan_id", "scheduled_date"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString
@EqualsAndHashCode
public class SubscriptionDelivery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "subscription_plan_id", nullable = false)
    private SubscriptionPlan plan;

    @Column(name = "order_id")
    private Long orderId;

    @Column(name = "scheduled_date", nullable = false)
    private LocalDate scheduledDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DeliveryStatus status = DeliveryStatus.PENDING;

    public enum DeliveryStatus {
        PENDING, PLACED, SKIPPED, FAILED
    }
}

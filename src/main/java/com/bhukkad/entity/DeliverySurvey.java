package com.bhukkad.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Post-delivery satisfaction survey submitted by a customer for a delivered order.
 *
 * <p>One survey per order; each rating is optional (1-5) so customers can skip
 * individual questions. Responses are aggregated per restaurant for analytics.
 */
@Entity
@Table(name = "delivery_surveys", indexes = {
        @Index(name = "idx_survey_customer", columnList = "customer_id")
}, uniqueConstraints = @UniqueConstraint(name = "uk_survey_order", columnNames = {"order_id"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DeliverySurvey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false, unique = true)
    private Order order;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @Column(name = "rating_delivery")
    private Integer ratingDelivery;

    @Column(name = "rating_food")
    private Integer ratingFood;

    @Column(name = "rating_speed")
    private Integer ratingSpeed;

    @Column(length = 1000)
    private String comment;

    @Column(name = "submitted_at", nullable = false, updatable = false)
    private LocalDateTime submittedAt;
}

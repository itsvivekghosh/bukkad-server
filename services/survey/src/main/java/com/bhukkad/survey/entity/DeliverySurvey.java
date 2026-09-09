package com.bhukkad.survey.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;

/**
 * Post-delivery satisfaction survey submitted by a customer for a delivered order.
 *
 * <p>One survey per order; each rating is optional (1-5) so customers can skip
 * individual questions. Responses are aggregated per restaurant for analytics.
 *
 * <p>The survey service owns its schema only: {@code orderId}, {@code customerId}
 * and {@code restaurantId} are plain columns (no cross-service JPA joins). The
 * ownership/delivery checks for an order live with the order service.
 */
@Entity
@Table(name = "delivery_surveys", indexes = {
        @Index(name = "idx_survey_customer", columnList = "customer_id"),
        @Index(name = "idx_survey_restaurant", columnList = "restaurant_id")
}, uniqueConstraints = @UniqueConstraint(name = "uk_survey_order", columnNames = {"order_id"}))
public class DeliverySurvey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false, unique = true)
    private Long orderId;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "restaurant_id")
    private Long restaurantId;

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

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public Long getCustomerId() {
        return customerId;
    }

    public void setCustomerId(Long customerId) {
        this.customerId = customerId;
    }

    public Long getRestaurantId() {
        return restaurantId;
    }

    public void setRestaurantId(Long restaurantId) {
        this.restaurantId = restaurantId;
    }

    public Integer getRatingDelivery() {
        return ratingDelivery;
    }

    public void setRatingDelivery(Integer ratingDelivery) {
        this.ratingDelivery = ratingDelivery;
    }

    public Integer getRatingFood() {
        return ratingFood;
    }

    public void setRatingFood(Integer ratingFood) {
        this.ratingFood = ratingFood;
    }

    public Integer getRatingSpeed() {
        return ratingSpeed;
    }

    public void setRatingSpeed(Integer ratingSpeed) {
        this.ratingSpeed = ratingSpeed;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    public LocalDateTime getSubmittedAt() {
        return submittedAt;
    }

    public void setSubmittedAt(LocalDateTime submittedAt) {
        this.submittedAt = submittedAt;
    }
}
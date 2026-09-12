package com.bhukkad.admin.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "restaurant_order_stats")
@EntityListeners(AuditingEntityListener.class)
@Getter @Setter
public class RestaurantOrderStat {
    @Id
    private Long restaurantId;
    @Column(nullable = false)
    private Long orderCount = 0L;
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal revenue = BigDecimal.ZERO;
    @Column(nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();
}
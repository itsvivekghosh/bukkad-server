package com.bhukkad.order.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import java.time.LocalDateTime;

@Entity
@Table(name = "group_orders", indexes = {
        @Index(name = "idx_group_orders_host", columnList = "hostUserId")
})
@EntityListeners(AuditingEntityListener.class)
@Getter @Setter
public class GroupOrder {

    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_PLACED = "PLACED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "host_user_id", nullable = false)
    private Long hostUserId;
    @Column(length = 100)
    private String title;
    @Column(nullable = false, length = 20)
    private String status = STATUS_OPEN;
    @Column(nullable = false)
    private Long restaurantId;
    @Column
    private LocalDateTime placedAt;
    @CreatedDate @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
    @LastModifiedDate @Column(nullable = false)
    private LocalDateTime updatedAt;
}
package com.bhukkad.order.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import java.time.LocalDateTime;

@Entity
@Table(name = "group_orders", indexes = {@Index(name = "idx_group_orders_host", columnList = "hostId")})
@EntityListeners(AuditingEntityListener.class)
@Getter @Setter
public class GroupOrder {
    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_CLOSED = "CLOSED";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private Long hostId;
    @Column(nullable = false)
    private Long restaurantId;
    @Column(nullable = false, length = 20)
    private String status;
    @CreatedDate @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
    @LastModifiedDate @Column(nullable = false)
    private LocalDateTime updatedAt;
}
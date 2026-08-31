package com.bhukkad.restaurant.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import java.time.LocalDateTime;

@Entity
@Table(name = "inventory_alerts")
@EntityListeners(AuditingEntityListener.class)
@Getter @Setter
public class InventoryAlert {
    public static final String TYPE_LOW_STOCK = "LOW_STOCK";
    public static final String TYPE_OUT_OF_STOCK = "OUT_OF_STOCK";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private Long menuItemId;
    @Column(nullable = false)
    private Integer threshold;
    @Column(nullable = false)
    private Integer currentStock;
    @Column(nullable = false, length = 30)
    private String alertType;
    @CreatedDate @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}

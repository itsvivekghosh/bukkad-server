package com.bhukkad.delivery.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import java.time.LocalDateTime;

@Entity
@Table(name = "delivery_agents")
@EntityListeners(AuditingEntityListener.class)
@Getter @Setter
public class DeliveryAgent {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 100)
    private String name;
    @Column(length = 20)
    private String phone;
    @Column(nullable = false)
    private Boolean isActive = true;

    @jakarta.persistence.Column(length = 30)
    private String vehicleType;

    @jakarta.persistence.Column(length = 30)
    private String vehicleNumber;

    /**
     * Active (assigned, not yet delivered) assignment count. Maintained by
     * conditional UPDATEs (V10): +1 on assign within the configured cap,
     * -1 on markDelivered — never mutated through JPA field writes so racing
     * dispatchers arbitrate inside the database.
     */
    @jakarta.persistence.Column(name = "active_load", nullable = false)
    private int activeLoad = 0;

    @CreatedDate @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
    @LastModifiedDate @Column(nullable = false)
    private LocalDateTime updatedAt;
}
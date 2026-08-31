package com.bhukkad.restaurant.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import java.time.LocalDateTime;

@Entity
@Table(name = "menu_versions", indexes = {
        @Index(name = "idx_menu_versions_restaurant", columnList = "restaurantId, version")
})
@EntityListeners(AuditingEntityListener.class)
@Getter @Setter
public class MenuVersion {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private Long restaurantId;
    @Column(nullable = false)
    private Integer version;
    private String snapshotJson;
    @CreatedDate @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}

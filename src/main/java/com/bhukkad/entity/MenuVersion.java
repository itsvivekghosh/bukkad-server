package com.bhukkad.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "menu_versions", uniqueConstraints = {
        @UniqueConstraint(name = "uq_menu_version", columnNames = {"restaurant_id", "version_number"})
}, indexes = {
        @Index(name = "idx_menu_version_restaurant", columnList = "restaurant_id")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MenuVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "restaurant_id", nullable = false)
    private Long restaurantId;

    @Column(name = "version_number", nullable = false)
    private Integer versionNumber;

    @Column(length = 100)
    private String label;

    @Column(name = "snapshot_json", nullable = false, columnDefinition = "MEDIUMTEXT")
    private String snapshotJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MenuVersionStatus status = MenuVersionStatus.DRAFT;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public enum MenuVersionStatus {
        DRAFT, PUBLISHED
    }
}
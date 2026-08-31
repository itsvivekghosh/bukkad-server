package com.bhukkad.admin.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import java.time.LocalDateTime;

@Entity
@Table(name = "api_keys", indexes = {
        @Index(name = "uk_api_key_hash", columnList = "keyHash", unique = true)
})
@EntityListeners(AuditingEntityListener.class)
@Getter @Setter
public class ApiKey {
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_REVOKED = "REVOKED";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 64)
    private String keyHash;
    @Column(nullable = false, length = 100)
    private String name;
    @Column(nullable = false, length = 20)
    private String status;
    @Column(nullable = false)
    private LocalDateTime expiresAt;
    @CreatedDate @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
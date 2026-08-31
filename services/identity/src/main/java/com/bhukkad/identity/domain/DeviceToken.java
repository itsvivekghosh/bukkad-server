package com.bhukkad.identity.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import java.time.LocalDateTime;

@Entity
@Table(name = "device_tokens", indexes = {@Index(name = "idx_device_tokens_user", columnList = "userId")})
@EntityListeners(AuditingEntityListener.class)
@Getter @Setter
public class DeviceToken {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private Long userId;
    @Column(nullable = false, length = 512)
    private String token;
    @Column(length = 20)
    private String deviceType;
    @CreatedDate @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
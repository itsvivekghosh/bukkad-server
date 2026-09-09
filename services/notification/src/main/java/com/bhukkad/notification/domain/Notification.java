package com.bhukkad.notification.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import java.time.LocalDateTime;

@Entity
@Table(name = "notifications", indexes = {
        @Index(name = "idx_notifications_channel_status", columnList = "channel, status"),
        @Index(name = "idx_notifications_recipient", columnList = "recipient, createdAt")
})
@EntityListeners(AuditingEntityListener.class)
@Getter @Setter
public class Notification {
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_SENT = "SENT";
    public static final String STATUS_FAILED = "FAILED";
    public static final String CHANNEL_EMAIL = "EMAIL";
    public static final String CHANNEL_SMS = "SMS";
    public static final String CHANNEL_PUSH = "PUSH";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 20)
    private String channel;
    @Column(nullable = false, length = 255)
    private String recipient;
    @Column(length = 100)
    private String template;
    @Column(length = 255)
    private String subject;
    private String body;
    @Column(nullable = false, length = 20)
    private String status;
    @Column(length = 100)
    private String providerRef;
    @Column(length = 1000)
    private String error;
    @CreatedDate @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
    @LastModifiedDate @Column(nullable = false)
    private LocalDateTime updatedAt;
}
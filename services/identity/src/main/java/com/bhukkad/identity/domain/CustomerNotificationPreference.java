package com.bhukkad.identity.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import java.time.LocalDateTime;

@Entity
@Table(name = "customer_notification_preferences", indexes = {
        @Index(name = "uk_notif_pref_customer_channel", columnList = "customerId, channel", unique = true)
})
@EntityListeners(AuditingEntityListener.class)
@Getter @Setter
public class CustomerNotificationPreference {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private Long customerId;
    @Column(nullable = false, length = 20)
    private String channel;
    @Column(nullable = false)
    private Boolean enabled = true;
    @CreatedDate @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
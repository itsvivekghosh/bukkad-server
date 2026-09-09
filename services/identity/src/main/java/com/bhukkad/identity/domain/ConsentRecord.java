package com.bhukkad.identity.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import java.time.LocalDateTime;

@Entity
@Table(name = "consent_records", indexes = {
        @Index(name = "uk_consent_user_purpose", columnList = "userId, purpose", unique = true)
})
@EntityListeners(AuditingEntityListener.class)
@Getter @Setter
public class ConsentRecord {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private Long userId;
    @Column(nullable = false, length = 50)
    private String purpose;
    @Column(nullable = false)
    private Boolean granted;
    @CreatedDate @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
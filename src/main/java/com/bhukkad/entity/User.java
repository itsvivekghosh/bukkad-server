package com.bhukkad.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.BatchSize;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.util.Set;

@Entity
@Table(name = "users", indexes = {
        @Index(name = "idx_user_role", columnList = "role"),
        @Index(name = "idx_user_active", columnList = "active")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Inheritance(strategy = InheritanceType.JOINED)
@EntityListeners(AuditingEntityListener.class)
@ToString
@EqualsAndHashCode
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserRole role;

    @Column(nullable = false)
    private Boolean active = true;

    @Column(nullable = false)
    private Boolean emailVerified = false;

    /** Whether the phone number has been verified via OTP. */
    @Column(name = "phone_verified", nullable = false)
    private Boolean phoneVerified = false;

    @Column(name = "phone_verified_at")
    private LocalDateTime phoneVerifiedAt;

    /** Whether the user has completed their profile (email, name, password). */
    @Column(name = "profile_completed", nullable = false)
    private Boolean profileCompleted = false;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;

    /** Whether the account requires a TOTP code at login (ADMIN / RESTAURANT_OWNER). */
    @Column(name = "totp_enabled")
    private Boolean totpEnabled = false;

    @BatchSize(size = 50)
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "user_referral_codes", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "code", length = 20, unique = true)
    // Never serialized: with open-in-view disabled, Jackson touching this lazy
    // collection outside the session blows up any endpoint that returns User
    // entities (e.g. review listings). No consumer reads it from JSON — codes
    // are exposed through dedicated DTOs instead.
    @JsonIgnore
    private Set<String> referralCodes;

    public enum UserRole {
        CUSTOMER, RESTAURANT_OWNER, DELIVERY_AGENT, ADMIN
    }
}
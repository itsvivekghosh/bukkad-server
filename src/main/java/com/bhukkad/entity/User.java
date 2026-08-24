package com.bhukkad.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.util.Set;

@Entity
@Table(name = "users", indexes = {
        @Index(name = "idx_user_email", columnList = "email", unique = true),
        @Index(name = "idx_user_phone", columnList = "phoneNumber"),
        @Index(name = "idx_user_role", columnList = "role"),
        @Index(name = "idx_user_active", columnList = "active"),
        @Index(name = "idx_user_email_active", columnList = "email, active"),
        @Index(name = "idx_user_role_active", columnList = "role, active")
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

    @Column(nullable = false, unique = true, length = 100)
    private String email;

    @JsonIgnore
    @Column(nullable = false, length = 255)
    private String password;

    @Column(nullable = false, length = 100)
    private String fullName;

    @Column(unique = true, length = 15)
    private String phoneNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserRole role;

    @Column(nullable = false)
    private Boolean active = true;

    @Column(nullable = false)
    private Boolean emailVerified = false;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;

    @Column(length = 500)
    private String profileImageUrl;

    @Column(name = "referrer_id")
    private Long referrerId;

    /** Base32-encoded TOTP secret for multi-factor auth; null when not enrolled. */
    @Column(name = "totp_secret")
    private String totpSecret;

    /** Whether the account requires a TOTP code at login (ADMIN / RESTAURANT_OWNER). */
    @Column(name = "totp_enabled")
    private Boolean totpEnabled = false;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_referral_codes", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "code", length = 20, unique = true)
    private Set<String> referralCodes;

    public enum UserRole {
        CUSTOMER, RESTAURANT_OWNER, DELIVERY_AGENT, ADMIN
    }
}
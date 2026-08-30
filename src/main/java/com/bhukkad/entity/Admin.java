package com.bhukkad.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * Admin account. Segregated onto its own {@code admins} table (V62) — admins
 * previously existed only as {@code role='ADMIN'} rows in the shared users
 * table with no dedicated storage for credentials and profile data.
 *
 * <p>Registry-level state (id, role, active, verification flags, MFA toggle)
 * still lives on {@link User}; this table carries the admin-specific
 * credentials and profile PII.</p>
 */
@Entity
@Table(name = "admins", indexes = {
        @Index(name = "idx_admin_phone", columnList = "phoneNumber")
})
@Data
@NoArgsConstructor
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
public class Admin extends User {

    /** Business identifier (email is unique per role table; nullable for phone-first admins). */
    @Column(nullable = true, length = 100)
    private String email;

    @JsonIgnore
    @Column(nullable = true, length = 255)
    private String password;

    @Column(nullable = true, length = 100)
    private String fullName;

    @Column(length = 15)
    private String phoneNumber;

    @Column(name = "profile_image_url", length = 500)
    private String profileImageUrl;

    /** Base32-encoded TOTP secret for multi-factor auth; null when not enrolled. */
    @JsonIgnore
    @Column(name = "totp_secret")
    private String totpSecret;

    public Admin(Long id) {
        setId(id);
    }
}

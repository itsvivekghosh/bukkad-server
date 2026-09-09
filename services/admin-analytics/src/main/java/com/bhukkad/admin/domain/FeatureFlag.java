package com.bhukkad.admin.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import java.time.LocalDateTime;

@Entity
@Table(name = "feature_flags", indexes = {
        @Index(name = "uk_feature_flag_name", columnList = "flagName", unique = true)
})
@EntityListeners(AuditingEntityListener.class)
@Getter @Setter
public class FeatureFlag {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 100)
    private String flagName;
    @Column(nullable = false)
    private Boolean enabled = false;
    @CreatedDate @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
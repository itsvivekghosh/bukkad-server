package com.bhukkad.restaurant.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import java.time.LocalDateTime;

@Entity
@Table(name = "customization_choices")
@EntityListeners(AuditingEntityListener.class)
@Getter @Setter
public class CustomizationChoice {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private Long menuItemId;
    @Column(nullable = false, length = 100)
    private String name;
    @Column(nullable = false)
    private Boolean isRequired = false;
    @CreatedDate @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}

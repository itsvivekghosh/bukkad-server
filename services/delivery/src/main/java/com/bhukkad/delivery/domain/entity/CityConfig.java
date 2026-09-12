package com.bhukkad.delivery.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import java.time.LocalDateTime;

@Entity
@Table(name = "city_configs")
@EntityListeners(AuditingEntityListener.class)
@Getter @Setter
public class CityConfig {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 100)
    private String cityName;
    @Column(nullable = false, length = 3)
    private String currency = "INR";
    @Column(nullable = false, length = 50)
    private String timezone = "Asia/Kolkata";
    @CreatedDate @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
package com.bhukkad.restaurant.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import java.time.LocalDateTime;

@Entity
@Table(name = "menu_item_ratings", indexes = {
        @Index(name = "uk_menu_item_rating", columnList = "menuItemId, customerId", unique = true)
})
@EntityListeners(AuditingEntityListener.class)
@Getter @Setter
public class MenuItemRating {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private Long menuItemId;
    @Column(nullable = false)
    private Long customerId;
    @Column(nullable = false)
    private Integer rating;
    @CreatedDate @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}

package com.bhukkad.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "customization_choices", indexes = {
        @Index(name = "idx_custom_choice_option", columnList = "customization_option_id"),
        @Index(name = "idx_custom_choice_available", columnList = "available")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CustomizationChoice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customization_option_id", nullable = false)
    private CustomizationOption customizationOption;

    @Column(nullable = false)
    private String name;

    private Double additionalPrice = 0.0;

    @Column(nullable = false)
    private Boolean available = true;
}
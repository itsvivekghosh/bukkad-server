package com.bhukkad.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "customization_options", indexes = {
        @Index(name = "idx_custom_opt_menu_item", columnList = "menu_item_id")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CustomizationOption {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "menu_item_id", nullable = false)
    private MenuItem menuItem;

    @Column(nullable = false)
    private String name; // e.g., "Size", "Add-ons", "Toppings"

    @Column(nullable = false)
    private Boolean required = false;

    @Column(nullable = false)
    private Boolean multipleSelection = false;

    private Integer minSelection = 0;

    private Integer maxSelection;

    @OneToMany(mappedBy = "customizationOption", cascade = CascadeType.ALL)
    private List<CustomizationChoice> choices = new ArrayList<>();
}
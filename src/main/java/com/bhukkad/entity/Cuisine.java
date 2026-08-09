package com.bhukkad.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "cuisines", indexes = {
        @Index(name = "idx_cuisine_name", columnList = "name", unique = true),
        @Index(name = "idx_cuisine_active", columnList = "active")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Cuisine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    private String imageUrl;

    @Column(nullable = false)
    private Boolean active = true;
}
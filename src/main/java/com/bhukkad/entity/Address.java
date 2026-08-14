package com.bhukkad.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "addresses", indexes = {
        @Index(name = "idx_address_customer", columnList = "customer_id"),
        @Index(name = "idx_address_type", columnList = "type"),
        @Index(name = "idx_address_default", columnList = "isDefault"),
        @Index(name = "idx_address_customer_default", columnList = "customer_id, isDefault"),
        @Index(name = "idx_address_pincode", columnList = "pincode"),
        @Index(name = "idx_address_city", columnList = "city"),
        @Index(name = "idx_address_location", columnList = "latitude, longitude")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Address {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id")
    private Customer customer;

    @Column(nullable = false)
    private String addressLine1;

    private String addressLine2;

    @Column(nullable = false)
    private String city;

    @Column(nullable = false)
    private String state;

    @Column(nullable = false)
    private String pincode;

    private String landmark;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private AddressType type;

    private String label; // Custom label like "Office", "Friend's Place"

    @Column(nullable = false)
    private Double latitude;

    @Column(nullable = false)
    private Double longitude;

    @Column(nullable = false)
    private Boolean isDefault = false;

    public enum AddressType {
        HOME, WORK, OTHER
    }
}
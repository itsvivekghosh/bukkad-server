package com.bhukkad.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "customers", indexes = {
        @Index(name = "idx_customer_loyalty_points", columnList = "loyaltyPoints"),
        @Index(name = "idx_customer_wallet_balance", columnList = "walletBalance")
})
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
public class Customer extends User {

    @OneToMany(mappedBy = "customer", cascade = CascadeType.ALL)
    private List<Address> addresses = new ArrayList<>();

    @OneToMany(mappedBy = "customer", cascade = CascadeType.ALL)
    private List<Order> orders = new ArrayList<>();

    @OneToOne(mappedBy = "customer", cascade = CascadeType.ALL)
    private Cart cart;

    private Integer loyaltyPoints = 0;

    @Column(name = "wallet_balance")
    private Double walletBalance = 0.0;
}
package com.bhukkad.growth.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * O(1) read model over the append-only {@link LoyaltyPointsLedger}
 * (ADR-005). Maintained exclusively through atomic single-statement SQL:
 * conditional upsert on credit, conditional decrement on redeem — a Java
 * read-modify-write (and a Redis-only decrement) can both lose updates.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "loyalty_point_balances")
public class LoyaltyPointBalance {

    @Id
    @Column(name = "customer_id")
    private Long customerId;

    @Column(nullable = false)
    private long points;

    @Column(name = "lifetime_points", nullable = false)
    private long lifetimePoints;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public int pointsAsInt() {
        return Math.toIntExact(points);
    }

    public int lifetimePointsAsInt() {
        return Math.toIntExact(lifetimePoints);
    }
}

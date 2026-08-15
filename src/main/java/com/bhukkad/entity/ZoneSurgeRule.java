package com.bhukkad.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Time-of-day surge pricing rule for a delivery zone.
 */
@Entity
@Table(name = "zone_surge_rules")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ZoneSurgeRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "zone_id", nullable = false)
    private DeliveryZone zone;

    /** Day of week 1-7 (Monday=1), null means every day. */
    private Integer dayOfWeek;

    @Column(nullable = false)
    private Integer startHour;

    @Column(nullable = false)
    private Integer endHour;

    @Column(nullable = false)
    private Double surgeMultiplier = 1.0;

    @Column(nullable = false)
    private Boolean isActive = true;
}

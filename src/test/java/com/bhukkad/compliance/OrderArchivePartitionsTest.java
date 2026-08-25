package com.bhukkad.compliance;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies the RANGE partition boundaries of the V55 {@code orders_archive}
 * table: each row maps to the correct quarter partition, and boundary rows
 * (the first day of a quarter) belong to the NEXT partition ("LESS THAN").
 */
class OrderArchivePartitionsTest {

    @Test
    void nextQuarterBoundary_midQuarter() {
        assertEquals(LocalDate.of(2024, 4, 1), OrderArchivePartitions.nextQuarterBoundary(LocalDate.of(2024, 2, 15)));
        assertEquals(LocalDate.of(2024, 7, 1), OrderArchivePartitions.nextQuarterBoundary(LocalDate.of(2024, 4, 30)));
        assertEquals(LocalDate.of(2025, 1, 1), OrderArchivePartitions.nextQuarterBoundary(LocalDate.of(2024, 12, 31)));
    }

    @Test
    void nextQuarterBoundary_firstDayOfQuarter_movesToNextQuarter() {
        // 2024-04-01 is the start of Q2; its boundary is the start of Q3.
        assertEquals(LocalDate.of(2024, 7, 1), OrderArchivePartitions.nextQuarterBoundary(LocalDate.of(2024, 4, 1)));
    }

    @Test
    void nextQuarterBoundary_january() {
        assertEquals(LocalDate.of(2024, 4, 1), OrderArchivePartitions.nextQuarterBoundary(LocalDate.of(2024, 1, 1)));
        assertEquals(LocalDate.of(2024, 4, 1), OrderArchivePartitions.nextQuarterBoundary(LocalDate.of(2024, 3, 31)));
    }

    @Test
    void nextQuarterBoundary_null_throws() {
        assertThrows(IllegalArgumentException.class, () -> OrderArchivePartitions.nextQuarterBoundary(null));
    }

    @Test
    void partitionFor_q1ToQ4() {
        LocalDate latest = LocalDate.of(2026, 4, 1);
        assertEquals("p2024q1", OrderArchivePartitions.partitionFor(LocalDate.of(2024, 2, 15), latest));
        assertEquals("p2024q4", OrderArchivePartitions.partitionFor(LocalDate.of(2024, 11, 15), latest));
        assertEquals("p2025q1", OrderArchivePartitions.partitionFor(LocalDate.of(2025, 1, 1), latest));
        assertEquals("p2025q3", OrderArchivePartitions.partitionFor(LocalDate.of(2025, 8, 20), latest));
    }

    @Test
    void partitionFor_boundaryDateBelongsToLaterPartition() {
        // 2024-04-01 is the LESS THAN cutoff for p2024q1; a row created then
        // belongs to p2024q2.
        assertEquals("p2024q2", OrderArchivePartitions.partitionFor(LocalDate.of(2024, 4, 1), LocalDate.of(2026, 4, 1)));
    }

    @Test
    void partitionFor_beyondLatestBoundary_isFuture() {
        assertEquals("p_future", OrderArchivePartitions.partitionFor(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 4, 1)));
        assertEquals("p_future", OrderArchivePartitions.partitionFor(LocalDate.of(2030, 1, 1), LocalDate.of(2026, 4, 1)));
    }

    @Test
    void partitionFor_null_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> OrderArchivePartitions.partitionFor(null, LocalDate.of(2026, 4, 1)));
        assertThrows(IllegalArgumentException.class,
                () -> OrderArchivePartitions.partitionFor(LocalDate.of(2024, 1, 1), null));
    }

    @Test
    void toDays_monotonicAndMatchesSqlEpoch() {
        long d1 = OrderArchivePartitions.toDays(LocalDate.of(2024, 1, 1));
        long d2 = OrderArchivePartitions.toDays(LocalDate.of(2024, 4, 1));
        long d3 = OrderArchivePartitions.toDays(LocalDate.of(2025, 1, 1));
        assertEquals(d3 - d1, 366L); // 2024 is a leap year
        assertEquals(d2 - d1, 91L);  // Jan 1 → Apr 1 = 91 days
    }

    @Test
    void toDays_null_throws() {
        assertThrows(IllegalArgumentException.class, () -> OrderArchivePartitions.toDays(null));
    }
}

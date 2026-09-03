package com.bhukkad.compliance;

import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;

/**
 * Computes the quarterly RANGE partition boundary for the {@code orders_archive}
 * table (V55 migration). Partition boundaries are expressed as
 * {@code TO_DAYS('YYYY-MM-DD')} "LESS THAN" cutoffs; a row belongs to the
 * partition whose upper bound is the first boundary strictly greater than the
 * row's created date.
 */
public final class OrderArchivePartitions {

    private OrderArchivePartitions() {
    }

    /**
     * Returns the first day of the quarter after {@code date} — i.e. the
     * "LESS THAN" boundary that contains {@code date}. Examples:
     * 2024-02-15 → 2024-04-01 (Q1 boundary), 2024-12-31 → 2025-01-01 (Q4 → Q1).
     */
    public static LocalDate nextQuarterBoundary(LocalDate date) {
        if (date == null) {
            throw new IllegalArgumentException("date must not be null");
        }
        int quarter = (date.getMonthValue() - 1) / 3; // 0-based quarter
        LocalDate firstOfNextQuarter = date.with(TemporalAdjusters.firstDayOfYear())
                .plusMonths((quarter + 1) * 3L);
        return firstOfNextQuarter;
    }

    /**
     * The partition name for a date, e.g. 2024-02-15 → {@code p2024q1},
     * 2024-12-31 → {@code p2024q4}, 2026-07-01 → {@code p_future} when the
     * date falls beyond the last explicit boundary.
     *
     * @param date   the row's created date
     * @param latest the latest explicit boundary (e.g. 2026-04-01 from V55);
     *               dates at/after this belong to {@code p_future}
     */
    public static String partitionFor(LocalDate date, LocalDate latest) {
        if (date == null || latest == null) {
            throw new IllegalArgumentException("date and latest must not be null");
        }
        if (!date.isBefore(latest)) {
            return "p_future";
        }
        int year = date.getYear();
        int quarter = date.getMonthValue() / 3; // 1..4 (3,6,9,12 → /3)
        if (date.getMonthValue() % 3 != 0) {
            quarter = date.getMonthValue() / 3 + 1;
        }
        return "p" + year + "q" + quarter;
    }

    /** TO_DAYS conversion used by the migration (number of days since year 0). */
    public static long toDays(LocalDate date) {
        if (date == null) {
            throw new IllegalArgumentException("date must not be null");
        }
        // Date-to-date arithmetic only: both operands are time-zone-free LocalDate
        // values, so the duration is unambiguous (no implicit zone conversion on
        // a LocalDateTime mid-flight, which is what the previous
        // LocalDateTime.of(0,1,1) + atStartOfDay() pair implied).
        long days = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.of(0, 1, 1), date);
        return days;
    }
}

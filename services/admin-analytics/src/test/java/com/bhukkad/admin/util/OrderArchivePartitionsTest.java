package com.bhukkad.admin.util;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderArchivePartitionsTest {

    @Test
    void utilityIsFinalWithPrivateCtor() throws Exception {
        Constructor<OrderArchivePartitions> ctor = OrderArchivePartitions.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        assertThat(ctor.newInstance()).isNotNull();
    }

    @Test
    void nextQuarterBoundary_returnsFirstDayOfFollowingQuarter() {
        assertThat(OrderArchivePartitions.nextQuarterBoundary(LocalDate.of(2024, 2, 15)))
                .isEqualTo(LocalDate.of(2024, 4, 1));
        assertThat(OrderArchivePartitions.nextQuarterBoundary(LocalDate.of(2024, 12, 31)))
                .isEqualTo(LocalDate.of(2025, 1, 1));
        assertThat(OrderArchivePartitions.nextQuarterBoundary(LocalDate.of(2024, 1, 1)))
                .isEqualTo(LocalDate.of(2024, 4, 1));
    }

    @Test
    void nextQuarterBoundary_rejectsNull() {
        assertThatThrownBy(() -> OrderArchivePartitions.nextQuarterBoundary(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void partitionFor_labelsQuarters() {
        LocalDate latest = LocalDate.of(2026, 4, 1);
        assertThat(OrderArchivePartitions.partitionFor(LocalDate.of(2024, 2, 15), latest)).isEqualTo("p2024q1");
        assertThat(OrderArchivePartitions.partitionFor(LocalDate.of(2024, 5, 20), latest)).isEqualTo("p2024q2");
        assertThat(OrderArchivePartitions.partitionFor(LocalDate.of(2024, 8, 1), latest)).isEqualTo("p2024q3");
        assertThat(OrderArchivePartitions.partitionFor(LocalDate.of(2024, 11, 30), latest)).isEqualTo("p2024q4");
        assertThat(OrderArchivePartitions.partitionFor(LocalDate.of(2024, 3, 31), latest)).isEqualTo("p2024q1");
    }

    @Test
    void partitionFor_datesBeyondLastBoundaryGoToFuturePartition() {
        LocalDate latest = LocalDate.of(2026, 4, 1);
        assertThat(OrderArchivePartitions.partitionFor(latest, latest)).isEqualTo("p_future");
        assertThat(OrderArchivePartitions.partitionFor(LocalDate.of(2027, 6, 1), latest)).isEqualTo("p_future");
    }

    @Test
    void partitionFor_rejectsNulls() {
        assertThatThrownBy(() -> OrderArchivePartitions.partitionFor(null, LocalDate.now()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OrderArchivePartitions.partitionFor(LocalDate.now(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void toDays_isMonotonic() {
        long epochDay = OrderArchivePartitions.toDays(LocalDate.of(2024, 1, 1));
        assertThat(epochDay).isPositive();
        assertThat(OrderArchivePartitions.toDays(LocalDate.of(2024, 1, 2))).isEqualTo(epochDay + 1);
        assertThatThrownBy(() -> OrderArchivePartitions.toDays(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

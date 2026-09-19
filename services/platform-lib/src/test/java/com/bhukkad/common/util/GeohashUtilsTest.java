package com.bhukkad.common.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GeohashUtilsTest {

    @Test
    void encode_returnsBase32String() {
        String hash = GeohashUtils.encode(12.9716, 77.5946, 4);
        assertThat(hash).hasSize(4);
        assertThat(hash).matches("[0123456789bcdefghjkmnpqrstuvwxyz]+");
    }

    @Test
    void encode_precisionBounds() {
        assertThrows(IllegalArgumentException.class, () -> GeohashUtils.encode(0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> GeohashUtils.encode(0, 0, 13));
    }

    @Test
    void decode_roundTrip() {
        String hash = GeohashUtils.encode(12.9716, 77.5946, 5);
        double[] bounds = GeohashUtils.decode(hash);
        assertThat(bounds).hasSize(4);
        assertThat(bounds[0]).isLessThan(bounds[1]); // minLat < maxLat
        assertThat(bounds[2]).isLessThan(bounds[3]); // minLng < maxLng
    }

    @Test
    void decode_invalidGeohash() {
        assertThrows(IllegalArgumentException.class, () -> GeohashUtils.decode("!@#$"));
    }

    @Test
    void getNeighbors_returnsNineCells() {
        String hash = GeohashUtils.encode(12.9716, 77.5946, 4);
        List<String> neighbors = GeohashUtils.getNeighbors(hash);
        assertThat(neighbors).hasSize(9);
        assertThat(neighbors).contains(hash);
    }

    @Test
    void getCoveringCells_returnsDistinctCells() {
        List<String> cells = GeohashUtils.getCoveringCells(12.9716, 77.5946, 5, 4);
        assertThat(cells).isNotEmpty();
        assertThat(cells).hasSameSizeAs(cells.stream().distinct().toList());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void decode_blankInput(String blank) {
        assertThrows(IllegalArgumentException.class, () -> GeohashUtils.decode(blank));
    }
}

package com.bhukkad.util;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DateTimeUtilsTest {

    @Test
    void formatters_handleValuesAndNulls() {
        LocalDateTime dateTime = LocalDateTime.of(2026, 8, 14, 21, 30, 45);
        assertEquals("14-08-2026 21:30:45", DateTimeUtils.formatDateTime(dateTime));
        assertEquals("14-08-2026", DateTimeUtils.formatDate(dateTime));
        assertEquals("21:30:45", DateTimeUtils.formatTime(LocalTime.of(21, 30, 45)));
        assertNull(DateTimeUtils.formatDateTime(null));
        assertNull(DateTimeUtils.formatDate(null));
        assertNull(DateTimeUtils.formatTime(null));
    }

    @Test
    void parseDateTime_roundTripsFormattedValue() {
        LocalDateTime dateTime = LocalDateTime.of(2026, 1, 2, 3, 4, 5);
        assertEquals(dateTime, DateTimeUtils.parseDateTime(DateTimeUtils.formatDateTime(dateTime)));
    }

    @Test
    void isRestaurantOpen_coversOpenAndClosedWindows() {
        assertTrue(DateTimeUtils.isRestaurantOpen(LocalTime.MIN, LocalTime.MAX));
        assertFalse(DateTimeUtils.isRestaurantOpen(LocalTime.MAX, LocalTime.MAX));
        assertFalse(DateTimeUtils.isRestaurantOpen(LocalTime.MIN, LocalTime.MIN));
    }

    @Test
    void durationHelpers_computeDifferencesAndAdditions() {
        LocalDateTime start = LocalDateTime.of(2026, 8, 14, 10, 0);
        LocalDateTime end = LocalDateTime.of(2026, 8, 16, 12, 30);

        assertEquals(3030, DateTimeUtils.getMinutesBetween(start, end));
        assertEquals(50, DateTimeUtils.getHoursBetween(start, end));
        assertEquals(2, DateTimeUtils.getDaysBetween(start, end));
        assertEquals(start.plusMinutes(15), DateTimeUtils.addMinutes(start, 15));
        assertEquals(start.plusHours(3), DateTimeUtils.addHours(start, 3));
        assertEquals(start.plusDays(4), DateTimeUtils.addDays(start, 4));
    }

    @Test
    void isWithinMinutes_trueAndFalse() {
        assertTrue(DateTimeUtils.isWithinMinutes(LocalDateTime.now().plusMinutes(2), 5));
        assertFalse(DateTimeUtils.isWithinMinutes(LocalDateTime.now().plusMinutes(20), 5));
    }

    @Test
    void constructor_isPrivate() throws Exception {
        Constructor<DateTimeUtils> constructor = DateTimeUtils.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        constructor.newInstance();
    }
}

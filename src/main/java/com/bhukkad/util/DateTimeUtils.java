package com.bhukkad.util;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

public class DateTimeUtils {

    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss");

    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("dd-MM-yyyy");

    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("HH:mm:ss");

    public static String formatDateTime(LocalDateTime dateTime) {
        return dateTime != null ? dateTime.format(DATE_TIME_FORMATTER) : null;
    }

    public static String formatDate(LocalDateTime dateTime) {
        return dateTime != null ? dateTime.format(DATE_FORMATTER) : null;
    }

    public static String formatTime(LocalTime time) {
        return time != null ? time.format(TIME_FORMATTER) : null;
    }

    public static LocalDateTime parseDateTime(String dateTimeString) {
        return LocalDateTime.parse(dateTimeString, DATE_TIME_FORMATTER);
    }

    public static boolean isRestaurantOpen(LocalTime openingTime, LocalTime closingTime) {
        LocalTime now = LocalTime.now();
        return !now.isBefore(openingTime) && !now.isAfter(closingTime);
    }

    public static long getMinutesBetween(LocalDateTime start, LocalDateTime end) {
        return ChronoUnit.MINUTES.between(start, end);
    }

    public static long getHoursBetween(LocalDateTime start, LocalDateTime end) {
        return ChronoUnit.HOURS.between(start, end);
    }

    public static long getDaysBetween(LocalDateTime start, LocalDateTime end) {
        return ChronoUnit.DAYS.between(start, end);
    }

    public static boolean isWithinMinutes(LocalDateTime dateTime, int minutes) {
        return Math.abs(getMinutesBetween(LocalDateTime.now(), dateTime)) <= minutes;
    }

    public static LocalDateTime addMinutes(LocalDateTime dateTime, int minutes) {
        return dateTime.plusMinutes(minutes);
    }

    public static LocalDateTime addHours(LocalDateTime dateTime, int hours) {
        return dateTime.plusHours(hours);
    }

    public static LocalDateTime addDays(LocalDateTime dateTime, int days) {
        return dateTime.plusDays(days);
    }

    private DateTimeUtils() {
        // Private constructor to prevent instantiation
    }
}
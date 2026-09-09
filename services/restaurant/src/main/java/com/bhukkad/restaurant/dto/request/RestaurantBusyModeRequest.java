package com.bhukkad.restaurant.dto.request;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * Request to enable busy mode for a restaurant.
 */
@Data
public class RestaurantBusyModeRequest {
    private LocalDateTime busyUntil;
    private Integer extraPrepMinutes;
}

package com.bhukkad.restaurant.api.dto.request;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

/**
 * Request to enable busy mode for a restaurant.
 */
public class RestaurantBusyModeRequest {

    @NotNull(message = "Busy until time is required")
    private LocalDateTime busyUntil;

    private Integer extraPrepMinutes;

    public LocalDateTime getBusyUntil() {
        return busyUntil;
    }

    public void setBusyUntil(LocalDateTime busyUntil) {
        this.busyUntil = busyUntil;
    }

    public Integer getExtraPrepMinutes() {
        return extraPrepMinutes;
    }

    public void setExtraPrepMinutes(Integer extraPrepMinutes) {
        this.extraPrepMinutes = extraPrepMinutes;
    }
}

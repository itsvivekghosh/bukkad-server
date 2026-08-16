package com.bhukkad.dto.request;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class RestaurantBusyModeRequest {
    private LocalDateTime busyUntil;
    private Integer extraPrepMinutes;
}

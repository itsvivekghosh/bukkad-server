package com.bhukkad.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class MenuItemRatingRequest {
    @NotNull
    private Long orderId;

    @NotNull
    private Long menuItemId;

    @NotNull
    @Min(1)
    @Max(5)
    private Integer rating;

    private String comment;
}

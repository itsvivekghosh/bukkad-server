package com.bhukkad.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class MenuItemRatingResponse {
    private Long id;
    private Long menuItemId;
    private Long orderId;
    private Integer rating;
    private String comment;
    private String createdAt;
}

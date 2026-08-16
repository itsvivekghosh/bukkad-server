package com.bhukkad.dto.request;

import lombok.Data;

import java.time.LocalDateTime;

/** Admin request to create or update a promo banner. */
@Data
public class PromoBannerRequest {
    private String title;
    private String subtitle;
    private String imageUrl;
    private String actionType;
    private String actionTarget;
    private Integer displayOrder;
    private Boolean isActive;
    private LocalDateTime startsAt;
    private LocalDateTime endsAt;
}

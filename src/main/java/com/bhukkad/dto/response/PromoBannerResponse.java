package com.bhukkad.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PromoBannerResponse {
    private Long id;
    private String title;
    private String subtitle;
    private String imageUrl;
    private String actionType;
    private String actionTarget;
    private Integer displayOrder;
}

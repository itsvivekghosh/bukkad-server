package com.bhukkad.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AffiliateCodeResponse {
    private Long id;
    private String code;
    private String name;
    private String channel;
    private Double rewardAmount;
    private Boolean isActive;
    private String createdAt;
}

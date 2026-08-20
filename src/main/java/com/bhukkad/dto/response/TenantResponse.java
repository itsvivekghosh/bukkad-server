package com.bhukkad.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TenantResponse {
    private Long id;
    private String name;
    private String domain;
    private String brandName;
    private String logoUrl;
    private String themeColor;
    private String currency;
    private Boolean isActive;
}

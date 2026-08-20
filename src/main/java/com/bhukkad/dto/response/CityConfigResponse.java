package com.bhukkad.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CityConfigResponse {
    private Long id;
    private String city;
    private String displayName;
    private String currency;
    private String timezone;
    private String supportedPaymentMethods;
    private Double defaultMinOrderAmount;
    private Boolean isServiceable;
    private Boolean isActive;
}

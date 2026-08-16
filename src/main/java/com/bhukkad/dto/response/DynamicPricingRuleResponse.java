package com.bhukkad.dto.response;

import com.bhukkad.entity.DynamicPricingRule;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;
import java.time.LocalTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DynamicPricingRuleResponse {
    private Long id;
    private Long restaurantId;
    private String name;
    private DynamicPricingRule.RuleType type;
    private Boolean active;
    private LocalTime startTime;
    private LocalTime endTime;
    private Integer dayOfWeek;
    private Double discountPercent;
    private Double surgePercent;
    private Double minOrderAmount;
    private Double maxDiscountAmount;
    private Integer priority;
    private LocalDateTime createdAt;
}
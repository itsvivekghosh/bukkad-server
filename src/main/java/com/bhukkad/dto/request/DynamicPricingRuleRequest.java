package com.bhukkad.dto.request;

import com.bhukkad.entity.DynamicPricingRule;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalTime;

@Data
public class DynamicPricingRuleRequest {
    @NotNull(message = "Rule name is required")
    private String name;

    @NotNull(message = "Rule type is required")
    private DynamicPricingRule.RuleType type;

    @NotNull(message = "Start time is required")
    private LocalTime startTime;

    @NotNull(message = "End time is required")
    private LocalTime endTime;

    private Integer dayOfWeek; // 1=Monday, 7=Sunday, 0=All days

    @DecimalMin(value = "0.0", message = "Discount percent must be >= 0")
    private Double discountPercent = 0.0;

    @DecimalMin(value = "0.0", message = "Surge percent must be >= 0")
    private Double surgePercent = 0.0;

    @DecimalMin(value = "0.0", message = "Min order amount must be >= 0")
    private Double minOrderAmount = 0.0;

    @DecimalMin(value = "0.0", message = "Max discount amount must be >= 0")
    private Double maxDiscountAmount = 0.0;

    private Integer priority = 0;
    private Boolean active = true;
}
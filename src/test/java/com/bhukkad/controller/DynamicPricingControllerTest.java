package com.bhukkad.controller;

import com.bhukkad.dto.request.DynamicPricingRuleRequest;
import com.bhukkad.dto.response.ApiResponse;
import com.bhukkad.dto.response.DynamicPricingRuleResponse;
import com.bhukkad.service.DynamicPricingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DynamicPricingControllerTest {

    @Mock
    private DynamicPricingService dynamicPricingService;

    @InjectMocks
    private DynamicPricingController controller;

    @Test
    void createRule_returnsCreatedRule() {
        DynamicPricingRuleRequest request = new DynamicPricingRuleRequest();
        DynamicPricingRuleResponse rule = DynamicPricingRuleResponse.builder().build();
        when(dynamicPricingService.createRule(1L, request)).thenReturn(rule);

        ResponseEntity<ApiResponse<DynamicPricingRuleResponse>> response = controller.createRule(1L, request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Pricing rule created successfully", response.getBody().getMessage());
        assertSame(rule, response.getBody().getData());
    }

    @Test
    void updateRule_returnsUpdatedRule() {
        DynamicPricingRuleRequest request = new DynamicPricingRuleRequest();
        DynamicPricingRuleResponse rule = DynamicPricingRuleResponse.builder().build();
        when(dynamicPricingService.updateRule(2L, request)).thenReturn(rule);

        ResponseEntity<ApiResponse<DynamicPricingRuleResponse>> response = controller.updateRule(2L, request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Pricing rule updated successfully", response.getBody().getMessage());
        assertSame(rule, response.getBody().getData());
    }

    @Test
    void deleteRule_returnsSuccess() {
        ResponseEntity<ApiResponse<Void>> response = controller.deleteRule(2L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Pricing rule deleted successfully", response.getBody().getMessage());
        verify(dynamicPricingService).deleteRule(2L);
    }

    @Test
    void getRules_returnsRulesForRestaurant() {
        List<DynamicPricingRuleResponse> rules = List.of(DynamicPricingRuleResponse.builder().build());
        when(dynamicPricingService.getRulesByRestaurant(1L)).thenReturn(rules);

        ResponseEntity<ApiResponse<List<DynamicPricingRuleResponse>>> response = controller.getRules(1L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertSame(rules, response.getBody().getData());
    }

    @Test
    void isHappyHourActive_returnsFlag() {
        when(dynamicPricingService.isHappyHourActive(1L)).thenReturn(true);

        ResponseEntity<ApiResponse<Boolean>> response = controller.isHappyHourActive(1L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(Boolean.TRUE, response.getBody().getData());
    }
}

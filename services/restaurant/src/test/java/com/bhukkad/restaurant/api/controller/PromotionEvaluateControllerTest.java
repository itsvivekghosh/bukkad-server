package com.bhukkad.restaurant.api.controller;

import com.bhukkad.restaurant.domain.service.impl.PromotionEngineService;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Security and validation tests for {@link PromotionEvaluateController}.
 */
@ExtendWith(MockitoExtension.class)
class PromotionEvaluateControllerTest {

    private final Validator validator =
            Validation.buildDefaultValidatorFactory().getValidator();

    @Mock
    private PromotionEngineService promotionEngineService;

    @InjectMocks
    private PromotionEvaluateController controller;

    // ------------------------------------------------------------------
    // Auth gate
    // ------------------------------------------------------------------

    @Test
    void evaluateBestDiscount_requiresCustomerOrAdmin() throws Exception {
        Method m = PromotionEvaluateController.class.getMethod("evaluateBestDiscount",
                PromotionEvaluateController.EvaluateRequest.class);
        PreAuthorize gate = m.getAnnotation(PreAuthorize.class);
        assertThat(gate).as("evaluateBestDiscount must carry @PreAuthorize").isNotNull();
        assertThat(gate.value()).contains("CUSTOMER").contains("ADMIN");
    }

    // ------------------------------------------------------------------
    // Happy path
    // ------------------------------------------------------------------

    @Test
    void evaluateBestDiscount_returnsPromotionResult() {
        var campaign = new com.bhukkad.restaurant.domain.entity.PromotionCampaign();
        when(promotionEngineService.evaluateBestDiscount(1L, 500.0, List.of()))
                .thenReturn(new PromotionEngineService.PromotionDiscountResult(campaign, 50.0, true));

        var request = new PromotionEvaluateController.EvaluateRequest(1L, 500.0, List.of());
        ResponseEntity<PromotionEngineService.PromotionDiscountResult> response =
                controller.evaluateBestDiscount(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().discountAmount()).isEqualTo(50.0);
        assertThat(response.getBody().freeDelivery()).isTrue();
    }

    // ------------------------------------------------------------------
    // Validation edge cases (bean-validation on the record)
    // ------------------------------------------------------------------

    @Test
    void evaluateBestDiscount_nullRestaurantId_violatesNotNull() {
        var violations = validator.validate(
                new PromotionEvaluateController.EvaluateRequest(null, 100.0, List.of()));
        assertThat(violations).extracting("propertyPath").anySatisfy(
                p -> assertThat(p.toString()).isEqualTo("restaurantId"));
    }

    @Test
    void evaluateBestDiscount_negativeSubtotal_violatesPositive() {
        var violations = validator.validate(
                new PromotionEvaluateController.EvaluateRequest(1L, -10.0, List.of()));
        assertThat(violations).extracting("propertyPath").anySatisfy(
                p -> assertThat(p.toString()).isEqualTo("subtotal"));
    }

    @Test
    void evaluateBestDiscount_nullCartItemPrice_violatesNotNull() {
        var badItem = new PromotionEvaluateController.EvaluateRequest.CartItemDto(1L, null, 1);
        var violations = validator.validate(
                new PromotionEvaluateController.EvaluateRequest(1L, 100.0, List.of(badItem)));
        assertThat(violations).extracting("propertyPath").anySatisfy(
                p -> assertThat(p.toString()).isEqualTo("cartItems[0].price"));
    }

    @Test
    void evaluateBestDiscount_zeroQuantity_violatesPositive() {
        var badItem = new PromotionEvaluateController.EvaluateRequest.CartItemDto(1L, 10.0, 0);
        var violations = validator.validate(
                new PromotionEvaluateController.EvaluateRequest(1L, 100.0, List.of(badItem)));
        assertThat(violations).extracting("propertyPath").anySatisfy(
                p -> assertThat(p.toString()).isEqualTo("cartItems[0].quantity"));
    }

    @Test
    void evaluateBestDiscount_nullMenuItemId_violatesNotNull() {
        var badItem = new PromotionEvaluateController.EvaluateRequest.CartItemDto(null, 10.0, 1);
        var violations = validator.validate(
                new PromotionEvaluateController.EvaluateRequest(1L, 100.0, List.of(badItem)));
        assertThat(violations).extracting("propertyPath").anySatisfy(
                p -> assertThat(p.toString()).isEqualTo("cartItems[0].menuItemId"));
    }

    @Test
    void evaluateBestDiscount_validRequest_passesValidation() {
        var violations = validator.validate(
                new PromotionEvaluateController.EvaluateRequest(1L, 100.0, List.of(
                        new PromotionEvaluateController.EvaluateRequest.CartItemDto(1L, 10.0, 1))));
        assertThat(violations).isEmpty();
    }

    @Test
    void evaluateBestDiscount_emptyCartItems_succeeds() {
        var noCampaign = new com.bhukkad.restaurant.domain.entity.PromotionCampaign();
        when(promotionEngineService.evaluateBestDiscount(1L, 100.0, List.of()))
                .thenReturn(new PromotionEngineService.PromotionDiscountResult(noCampaign, 0.0, false));

        var request = new PromotionEvaluateController.EvaluateRequest(1L, 100.0, List.of());
        ResponseEntity<PromotionEngineService.PromotionDiscountResult> response =
                controller.evaluateBestDiscount(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().discountAmount()).isEqualTo(0.0);
        assertThat(response.getBody().freeDelivery()).isFalse();
    }
}

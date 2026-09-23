package com.bhukkad.restaurant.api.controller;

import com.bhukkad.restaurant.domain.service.impl.PromotionEngineService;
import com.bhukkad.restaurant.domain.service.impl.PromotionEngineService.PromotionDiscountResult;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/promotions/evaluate")
@Validated
@RequiredArgsConstructor
public class PromotionEvaluateController {

    private final PromotionEngineService promotionEngineService;

    public record EvaluateRequest(
            @NotNull Long restaurantId,
            @Positive double subtotal,
            @Valid List<CartItemDto> cartItems) {
        public record CartItemDto(
                @NotNull Long menuItemId,
                @NotNull @Positive Double price,
                @NotNull @Positive Integer quantity) {
        }
    }

    @PostMapping
    @PreAuthorize("hasRole('CUSTOMER') or hasRole('ADMIN')")
    public ResponseEntity<PromotionDiscountResult> evaluateBestDiscount(@Valid @RequestBody EvaluateRequest request) {
        var cartItems = request.cartItems() == null ? List.<PromotionEngineService.CartItemDto>of()
                : request.cartItems().stream()
                        .map(dto -> new PromotionEngineService.CartItemDto(dto.menuItemId(), dto.price(), dto.quantity()))
                        .toList();
        return ResponseEntity.ok(promotionEngineService.evaluateBestDiscount(
                request.restaurantId(), request.subtotal(), cartItems));
    }
}

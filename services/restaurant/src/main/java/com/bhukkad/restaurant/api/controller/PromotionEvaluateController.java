package com.bhukkad.restaurant.api.controller;

import com.bhukkad.restaurant.domain.service.impl.PromotionEngineService;
import com.bhukkad.restaurant.domain.service.impl.PromotionEngineService.PromotionDiscountResult;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/promotions/evaluate")
@RequiredArgsConstructor
public class PromotionEvaluateController {

    private final PromotionEngineService promotionEngineService;

    public record EvaluateRequest(Long restaurantId, double subtotal, List<CartItemDto> cartItems) {
        public record CartItemDto(Long menuItemId, Double price, Integer quantity) {
        }
    }

    @PostMapping
    public ResponseEntity<PromotionDiscountResult> evaluateBestDiscount(@RequestBody EvaluateRequest request) {
        var cartItems = request.cartItems() == null ? List.<PromotionEngineService.CartItemDto>of()
                : request.cartItems().stream()
                        .map(dto -> new PromotionEngineService.CartItemDto(dto.menuItemId(), dto.price(), dto.quantity()))
                        .toList();
        return ResponseEntity.ok(promotionEngineService.evaluateBestDiscount(
                request.restaurantId(), request.subtotal(), cartItems));
    }
}

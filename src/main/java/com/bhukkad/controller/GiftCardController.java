package com.bhukkad.controller;

import com.bhukkad.config.ApiPaths;
import com.bhukkad.dto.request.GiftCardPurchaseRequest;
import com.bhukkad.dto.request.GiftCardRedeemRequest;
import com.bhukkad.dto.response.ApiResponse;
import com.bhukkad.dto.response.GiftCardResponse;
import com.bhukkad.service.GiftCardService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping(ApiPaths.V1_PREFIX + "/gift-cards")
@RequiredArgsConstructor
public class GiftCardController {

    private final GiftCardService giftCardService;

    @PostMapping("/purchase")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<ApiResponse<GiftCardResponse>> purchaseGiftCard(
            @Valid @RequestBody GiftCardPurchaseRequest request) {
        GiftCardResponse giftCard = giftCardService.purchaseGiftCard(request);
        return ResponseEntity.ok(ApiResponse.success("Gift card purchased successfully", giftCard));
    }

    @PostMapping("/redeem")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<ApiResponse<GiftCardResponse>> redeemGiftCard(
            @Valid @RequestBody GiftCardRedeemRequest request) {
        GiftCardResponse giftCard = giftCardService.redeemGiftCard(request);
        return ResponseEntity.ok(ApiResponse.success("Gift card redeemed successfully", giftCard));
    }

    @GetMapping("/my-cards")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<ApiResponse<List<GiftCardResponse>>> getMyGiftCards() {
        List<GiftCardResponse> cards = giftCardService.getMyGiftCards();
        return ResponseEntity.ok(ApiResponse.success(cards));
    }

    @GetMapping("/received")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<ApiResponse<List<GiftCardResponse>>> getGiftCardsReceived() {
        List<GiftCardResponse> cards = giftCardService.getGiftCardsPurchasedForMe();
        return ResponseEntity.ok(ApiResponse.success(cards));
    }

    @GetMapping("/{code}")
    public ResponseEntity<ApiResponse<GiftCardResponse>> getGiftCardByCode(@PathVariable String code) {
        GiftCardResponse giftCard = giftCardService.getGiftCardByCode(code);
        return ResponseEntity.ok(ApiResponse.success(giftCard));
    }
}
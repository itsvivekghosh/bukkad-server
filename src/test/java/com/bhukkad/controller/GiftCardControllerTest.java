package com.bhukkad.controller;

import com.bhukkad.dto.request.GiftCardPurchaseRequest;
import com.bhukkad.dto.request.GiftCardRedeemRequest;
import com.bhukkad.dto.response.ApiResponse;
import com.bhukkad.dto.response.GiftCardResponse;
import com.bhukkad.service.GiftCardService;
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
class GiftCardControllerTest {

    @Mock
    private GiftCardService giftCardService;

    @InjectMocks
    private GiftCardController controller;

    @Test
    void purchaseGiftCard_returnsPurchasedCard() {
        GiftCardPurchaseRequest request = new GiftCardPurchaseRequest();
        GiftCardResponse card = GiftCardResponse.builder().build();
        when(giftCardService.purchaseGiftCard(request)).thenReturn(card);

        ResponseEntity<ApiResponse<GiftCardResponse>> response = controller.purchaseGiftCard(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Gift card purchased successfully", response.getBody().getMessage());
        assertSame(card, response.getBody().getData());
    }

    @Test
    void redeemGiftCard_returnsRedeemedCard() {
        GiftCardRedeemRequest request = new GiftCardRedeemRequest();
        GiftCardResponse card = GiftCardResponse.builder().build();
        when(giftCardService.redeemGiftCard(request)).thenReturn(card);

        ResponseEntity<ApiResponse<GiftCardResponse>> response = controller.redeemGiftCard(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Gift card redeemed successfully", response.getBody().getMessage());
        assertSame(card, response.getBody().getData());
    }

    @Test
    void getMyGiftCards_returnsCards() {
        List<GiftCardResponse> cards = List.of(GiftCardResponse.builder().build());
        when(giftCardService.getMyGiftCards()).thenReturn(cards);

        ResponseEntity<ApiResponse<List<GiftCardResponse>>> response = controller.getMyGiftCards();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertSame(cards, response.getBody().getData());
    }

    @Test
    void getGiftCardsReceived_returnsCards() {
        List<GiftCardResponse> cards = List.of(GiftCardResponse.builder().build());
        when(giftCardService.getGiftCardsPurchasedForMe()).thenReturn(cards);

        ResponseEntity<ApiResponse<List<GiftCardResponse>>> response = controller.getGiftCardsReceived();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertSame(cards, response.getBody().getData());
    }

    @Test
    void getGiftCardByCode_returnsCard() {
        GiftCardResponse card = GiftCardResponse.builder().build();
        when(giftCardService.getGiftCardByCode("GC-123")).thenReturn(card);

        ResponseEntity<ApiResponse<GiftCardResponse>> response = controller.getGiftCardByCode("GC-123");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertSame(card, response.getBody().getData());
        verify(giftCardService).getGiftCardByCode("GC-123");
    }
}

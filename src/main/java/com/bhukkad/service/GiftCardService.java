package com.bhukkad.service;

import com.bhukkad.dto.request.GiftCardPurchaseRequest;
import com.bhukkad.dto.request.GiftCardRedeemRequest;
import com.bhukkad.dto.response.GiftCardResponse;

import java.util.List;

public interface GiftCardService {
    GiftCardResponse purchaseGiftCard(GiftCardPurchaseRequest request);
    GiftCardResponse redeemGiftCard(GiftCardRedeemRequest request);
    GiftCardResponse getGiftCardByCode(String code);
    List<GiftCardResponse> getMyGiftCards();
    List<GiftCardResponse> getGiftCardsPurchasedForMe();
}
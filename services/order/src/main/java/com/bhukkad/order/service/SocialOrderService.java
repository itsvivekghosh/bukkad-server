package com.bhukkad.order.service;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.order.domain.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Social order depth (Priority 4): group orders, gift cards, subscriptions.
 */
@Service
@RequiredArgsConstructor
public class SocialOrderService {

    /** Per-card ceiling for the simulated issuance flow (no gateway charge yet). */
    public static final BigDecimal MAX_GIFT_CARD_AMOUNT = new BigDecimal("10000.00");

    private final GroupOrderRepository groupRepository;
    private final GroupOrderMemberRepository memberRepository;
    private final GiftCardRepository giftCardRepository;
    private final SubscriptionRepository subscriptionRepository;

    @Transactional
    public GroupOrder createGroupOrder(Long hostId, Long restaurantId) {
        GroupOrder group = new GroupOrder();
        group.setHostUserId(hostId);
        group.setRestaurantId(restaurantId);
        group.setStatus(GroupOrder.STATUS_OPEN);
        GroupOrder saved = groupRepository.save(group);

        GroupOrderMember host = new GroupOrderMember();
        host.setGroupOrderId(saved.getId());
        host.setCustomerId(hostId);
        host.setStatus("HOST");
        memberRepository.save(host);
        return saved;
    }

    @Transactional(readOnly = true)
    public GroupOrder groupOrder(Long groupOrderId) {
        return groupRepository.findById(groupOrderId)
                .orElseThrow(() -> new ResourceNotFoundException("Group order not found"));
    }

    @Transactional
    public GroupOrderMember join(Long groupOrderId, Long customerId) {
        if (memberRepository.findByGroupOrderIdAndCustomerId(groupOrderId, customerId).isPresent()) {
            throw new BusinessException("Already joined this group order");
        }
        GroupOrderMember member = new GroupOrderMember();
        member.setGroupOrderId(groupOrderId);
        member.setCustomerId(customerId);
        member.setStatus("MEMBER");
        return memberRepository.save(member);
    }

    @Transactional(readOnly = true)
    public List<GroupOrderMember> members(Long groupOrderId) {
        return memberRepository.findByGroupOrderId(groupOrderId);
    }

    @Transactional
    public GiftCard issueGiftCard(Long purchasedBy, BigDecimal amount) {
        if (amount.signum() <= 0) {
            throw new BusinessException("Gift card amount must be positive");
        }
        if (amount.compareTo(MAX_GIFT_CARD_AMOUNT) > 0) {
            throw new BusinessException("Gift card amount exceeds the per-card limit");
        }
        GiftCard card = new GiftCard();
        // 128 bits of entropy: gift cards are bearer instruments; an 8-hex-char
        // code was enumerable by brute force.
        card.setCode("GC-" + UUID.randomUUID().toString().replace("-", ""));
        card.setBalance(amount);
        card.setAmount(amount);
        card.setPurchasedBy(purchasedBy);
        card.setStatus(GiftCard.STATUS_ACTIVE);
        GiftCard saved = giftCardRepository.save(card);
        return saved;
    }

    /**
     * Atomic conditional redemption: the balance check and the decrement are
     * one UPDATE, so concurrent redemptions cannot both pass the check and
     * overdraw (lost-update / double-spend). The entity fields
     * {@code redeemedBy}/{@code redeemedAt} are stamped on the loaded row.
     */
    /** Fetches a gift card by code (balance check surface). */
    @Transactional(readOnly = true)
    public GiftCard giftCardByCode(String code) {
        if (code == null || code.isBlank()) {
            throw new BusinessException("code is required");
        }
        return giftCardRepository.findByCodeAndStatus(code, GiftCard.STATUS_ACTIVE)
                .orElseGet(() -> giftCardRepository.findByCode(code).orElse(null));
    }

    @Transactional
    public BigDecimal redeemGiftCard(String code, Long redeemedBy, BigDecimal amount) {
        GiftCard card = giftCardRepository.findByCodeAndStatus(code, GiftCard.STATUS_ACTIVE)
                .orElseThrow(() -> new ResourceNotFoundException("Invalid or inactive gift card"));
        // Amount omitted → redeem the FULL remaining balance (monolith parity
        // for the single-code redemption flow).
        BigDecimal effectiveAmount = amount == null ? card.getBalance() : amount;
        if (effectiveAmount.signum() <= 0) {
            throw new BusinessException("Redemption amount must be positive");
        }
        int updated = giftCardRepository.redeem(code, effectiveAmount, redeemedBy);
        if (updated == 0) {
            throw new BusinessException("Insufficient gift card balance");
        }
        BigDecimal remaining = card.getBalance().subtract(effectiveAmount);
        if (remaining.signum() == 0) {
            giftCardRepository.updateStatus(code, GiftCard.STATUS_EXHAUSTED);
        }
        return remaining;
    }

    @Transactional
    public Subscription subscribe(Long customerId, Long restaurantId, String plan) {
        Subscription subscription = new Subscription();
        subscription.setCustomerId(customerId);
        subscription.setRestaurantId(restaurantId);
        subscription.setPlan(plan);
        subscription.setStatus(Subscription.STATUS_ACTIVE);
        return subscriptionRepository.save(subscription);
    }

    @Transactional(readOnly = true)
    public List<Subscription> subscriptions(Long customerId) {
        return subscriptionRepository.findByCustomerIdAndStatus(customerId, Subscription.STATUS_ACTIVE);
    }
}
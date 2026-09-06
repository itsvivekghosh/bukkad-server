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
    public GiftCard issueGiftCard(BigDecimal amount) {
        if (amount.signum() <= 0) {
            throw new BusinessException("Gift card amount must be positive");
        }
        GiftCard card = new GiftCard();
        card.setCode("GC-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        card.setBalance(amount);
        card.setStatus(GiftCard.STATUS_ACTIVE);
        return giftCardRepository.save(card);
    }

    @Transactional
    public BigDecimal redeemGiftCard(String code, BigDecimal amount) {
        GiftCard card = giftCardRepository.findByCodeAndStatus(code, GiftCard.STATUS_ACTIVE)
                .orElseThrow(() -> new ResourceNotFoundException("Invalid or inactive gift card"));
        if (amount.compareTo(card.getBalance()) > 0) {
            throw new BusinessException("Insufficient gift card balance");
        }
        card.setBalance(card.getBalance().subtract(amount));
        if (card.getBalance().signum() == 0) {
            card.setStatus(GiftCard.STATUS_EXHAUSTED);
        }
        giftCardRepository.save(card);
        return card.getBalance();
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
package com.bhukkad.serviceImpl;

import com.bhukkad.config.GiftCardProperties;
import com.bhukkad.dto.request.GiftCardPurchaseRequest;
import com.bhukkad.dto.request.GiftCardRedeemRequest;
import com.bhukkad.dto.response.GiftCardResponse;
import com.bhukkad.entity.Customer;
import com.bhukkad.entity.GiftCard;
import com.bhukkad.entity.Order;
import com.bhukkad.exception.BusinessException;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.repository.CustomerRepository;
import com.bhukkad.repository.GiftCardRepository;
import com.bhukkad.repository.OrderRepository;
import com.bhukkad.security.SecurityUtils;
import com.bhukkad.service.GiftCardService;
import com.bhukkad.util.NotificationHelper;
import com.bhukkad.util.PriceCalculator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class GiftCardServiceImpl implements GiftCardService {

    private final GiftCardRepository giftCardRepository;
    private final CustomerRepository customerRepository;
    private final OrderRepository orderRepository;
    private final SecurityUtils securityUtils;
    private final NotificationHelper notificationHelper;
    private final GiftCardProperties giftCardProperties;

    private static final SecureRandom RANDOM = new SecureRandom();

    @Override
    @Transactional
    public GiftCardResponse purchaseGiftCard(GiftCardPurchaseRequest request) {
        Long customerId = securityUtils.getCurrentUserId();
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found"));

        // Validate amount
        if (request.getAmount() < giftCardProperties.getMinAmount()) {
            throw new BusinessException("Minimum gift card amount is " + giftCardProperties.getMinAmount());
        }

        if (request.getAmount() > giftCardProperties.getMaxAmount()) {
            throw new BusinessException("Maximum gift card amount is " + giftCardProperties.getMaxAmount());
        }

        // Generate unique code
        String code = generateGiftCardCode();

        GiftCard giftCard = new GiftCard();
        giftCard.setCode(code);
        giftCard.setAmount(request.getAmount());
        giftCard.setBalance(request.getAmount());
        giftCard.setStatus(GiftCard.Status.ACTIVE);
        giftCard.setPurchasedBy(customer);
        giftCard.setRecipientEmail(request.getRecipientEmail());
        giftCard.setRecipientName(request.getRecipientName());
        giftCard.setMessage(request.getMessage());
        giftCard.setExpiresAt(request.getExpiresAt().atStartOfDay().plusDays(1).minusSeconds(1));

        giftCard = giftCardRepository.save(giftCard);

        // Send notification
        try {
            notificationHelper.sendGiftCardNotification(
                    request.getRecipientEmail(),
                    request.getRecipientName(),
                    code,
                    request.getAmount(),
                    request.getMessage());
        } catch (Exception ex) {
            log.warn("Failed to send gift card notification: {}", ex.getMessage());
        }

        log.info("Gift card purchased | code={} | amount={} | purchaser={}",
                code, request.getAmount(), customerId);

        return toResponse(giftCard);
    }

    @Override
    @Transactional
    public GiftCardResponse redeemGiftCard(GiftCardRedeemRequest request) {
        Long customerId = securityUtils.getCurrentUserId();
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found"));

        GiftCard giftCard = giftCardRepository.findByCode(request.getCode().toUpperCase())
                .orElseThrow(() -> new BusinessException("Invalid gift card code"));

        if (giftCard.getStatus() != GiftCard.Status.ACTIVE) {
            throw new BusinessException("Gift card is not valid");
        }

        if (giftCard.getExpiresAt().isBefore(LocalDateTime.now())) {
            giftCard.setStatus(GiftCard.Status.EXPIRED);
            giftCardRepository.save(giftCard);
            throw new BusinessException("Gift card has expired");
        }

        if (giftCard.getBalance() <= 0) {
            giftCard.setStatus(GiftCard.Status.REDEEMED);
            giftCardRepository.save(giftCard);
            throw new BusinessException("Gift card has no remaining balance");
        }

        // Redeem the full balance
        double redeemedAmount = giftCard.getBalance();
        giftCard.setBalance(0.0);
        giftCard.setStatus(GiftCard.Status.REDEEMED);
        giftCard.setRedeemedBy(customer);
        giftCard.setRedeemedAt(LocalDateTime.now());

        giftCardRepository.save(giftCard);

        log.info("Gift card redeemed | code={} | amount={} | redeemedBy={}",
                request.getCode(), redeemedAmount, customerId);

        return toResponse(giftCard);
    }

    @Override
    public GiftCardResponse getGiftCardByCode(String code) {
        GiftCard giftCard = giftCardRepository.findByCode(code.toUpperCase())
                .orElseThrow(() -> new ResourceNotFoundException("Gift card not found"));

        // Only allow purchaser or redeemed user to view
        Long customerId = securityUtils.getCurrentUserId();
        if (!giftCard.getPurchasedBy().getId().equals(customerId) &&
                (giftCard.getRedeemedBy() == null || !giftCard.getRedeemedBy().getId().equals(customerId))) {
            throw new BusinessException("Not authorized to view this gift card");
        }

        return toResponse(giftCard);
    }

    @Override
    public List<GiftCardResponse> getMyGiftCards() {
        Long customerId = securityUtils.getCurrentUserId();
        return giftCardRepository.findByPurchasedById(customerId).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Override
    public List<GiftCardResponse> getGiftCardsPurchasedForMe() {
        Long customerId = securityUtils.getCurrentUserId();
        String customerEmail = customerRepository.findById(customerId)
                .map(Customer::getEmail)
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found"));

        return giftCardRepository.findByRecipientEmailAndStatus(customerEmail, GiftCard.Status.ACTIVE).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    private String generateGiftCardCode() {
        String code;
        do {
            code = "GIFT-" + RANDOM.nextInt(1000000, 9999999);
        } while (giftCardRepository.existsByCode(code));
        return code;
    }

    private GiftCardResponse toResponse(GiftCard giftCard) {
        return GiftCardResponse.builder()
                .id(giftCard.getId())
                .code(giftCard.getCode())
                .amount(giftCard.getAmount())
                .balance(giftCard.getBalance())
                .status(giftCard.getStatus())
                .recipientEmail(giftCard.getRecipientEmail())
                .recipientName(giftCard.getRecipientName())
                .message(giftCard.getMessage())
                .expiresAt(giftCard.getExpiresAt())
                .createdAt(giftCard.getCreatedAt())
                .redeemedAt(giftCard.getRedeemedAt())
                .build();
    }
}
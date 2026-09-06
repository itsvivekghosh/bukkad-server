package com.bhukkad.delivery.service;

import com.bhukkad.delivery.client.PaymentServiceClient;
import com.bhukkad.delivery.domain.OrderDeliveryProof;
import com.bhukkad.delivery.domain.OrderDeliveryProofRepository;
import com.bhukkad.delivery.domain.ZoneSurgeRule;
import com.bhukkad.delivery.domain.ZoneSurgeRuleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Delivery ops depth (Batch D): rider earnings, delivery proofs, zone surge.
 * Rider earnings are delegated to the payment service.
 */
@Service
@RequiredArgsConstructor
public class DeliveryOpsService {

    private final OrderDeliveryProofRepository proofRepository;
    private final ZoneSurgeRuleRepository surgeRepository;
    private final PaymentServiceClient paymentClient;

    /**
     * Records rider earning via payment service.
     */
    public Map<String, Object> recordEarning(Long agentId, Long orderId, BigDecimal amount) {
        return paymentClient.recordEarning(agentId, orderId, amount);
    }

    @Transactional
    public OrderDeliveryProof recordProof(Long orderId, String photoUrl, String notes, String signature) {
        OrderDeliveryProof proof = new OrderDeliveryProof();
        proof.setOrderId(orderId);
        proof.setPhotoUrl(photoUrl);
        proof.setNotes(notes);
        proof.setSignature(signature);
        proof.setCreatedAt(LocalDateTime.now());
        return proofRepository.save(proof);
    }

    /**
     * Gets rider earnings via payment service.
     */
    public List<Map<String, Object>> earnings(Long agentId) {
        return paymentClient.getEarnings(agentId);
    }

    @Transactional(readOnly = true)
    public List<ZoneSurgeRule> activeSurgeRules(Long zoneId) {
        return surgeRepository.findByZoneIdAndActiveTrue(zoneId);
    }
}

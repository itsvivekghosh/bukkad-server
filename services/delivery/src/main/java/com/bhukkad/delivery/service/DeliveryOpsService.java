package com.bhukkad.delivery.service;

import com.bhukkad.delivery.domain.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Delivery ops depth (Batch D): rider earnings, delivery proofs, zone surge.
 */
@Service
@RequiredArgsConstructor
public class DeliveryOpsService {

    private final RiderEarningRepository earningRepository;
    private final OrderDeliveryProofRepository proofRepository;
    private final ZoneSurgeRuleRepository surgeRepository;

    @Transactional
    public RiderEarning recordEarning(Long agentId, Long orderId, BigDecimal amount) {
        RiderEarning earning = new RiderEarning();
        earning.setAgentId(agentId);
        earning.setOrderId(orderId);
        earning.setAmount(amount);
        earning.setStatus("PENDING");
        return earningRepository.save(earning);
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

    @Transactional(readOnly = true)
    public List<RiderEarning> earnings(Long agentId) {
        return earningRepository.findByAgentId(agentId);
    }

    @Transactional(readOnly = true)
    public List<ZoneSurgeRule> activeSurgeRules(Long zoneId) {
        return surgeRepository.findByZoneIdAndActiveTrue(zoneId);
    }
}
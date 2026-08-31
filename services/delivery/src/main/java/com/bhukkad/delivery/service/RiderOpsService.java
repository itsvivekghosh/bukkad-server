package com.bhukkad.delivery.service;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.delivery.domain.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Rider real-time ops (Priority 5): location pings, COD wallet, batch dispatch.
 */
@Service
@RequiredArgsConstructor
public class RiderOpsService {

    private final RiderLocationUpdateRepository locationRepository;
    private final AgentCodWalletRepository codWalletRepository;
    private final RiderDeliveryBatchRepository batchRepository;
    private final RiderDeliveryBatchOrderRepository batchOrderRepository;

    @Transactional
    public RiderLocationUpdate reportLocation(Long agentId, double lat, double lng) {
        RiderLocationUpdate update = new RiderLocationUpdate();
        update.setAgentId(agentId);
        update.setLatitude(lat);
        update.setLongitude(lng);
        update.setRecordedAt(LocalDateTime.now());
        return locationRepository.save(update);
    }

    @Transactional(readOnly = true)
    public List<RiderLocationUpdate> recentLocations(Long agentId) {
        return locationRepository.findByAgentId(agentId);
    }

    @Transactional
    public AgentCodWallet creditCod(Long agentId, BigDecimal amount) {
        if (amount.signum() <= 0) {
            throw new BusinessException("COD credit must be positive");
        }
        return codWalletRepository.findByAgentId(agentId)
                .map(wallet -> {
                    wallet.setBalance(wallet.getBalance().add(amount));
                    wallet.setUpdatedAt(LocalDateTime.now());
                    return codWalletRepository.save(wallet);
                })
                .orElseGet(() -> {
                    AgentCodWallet wallet = new AgentCodWallet();
                    wallet.setAgentId(agentId);
                    wallet.setBalance(amount);
                    return codWalletRepository.save(wallet);
                });
    }

    @Transactional
    public RiderDeliveryBatch createBatch(Long agentId, List<Long> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) {
            throw new BusinessException("Batch requires at least one order");
        }
        RiderDeliveryBatch batch = new RiderDeliveryBatch();
        batch.setAgentId(agentId);
        batch.setStatus("ASSIGNED");
        RiderDeliveryBatch saved = batchRepository.save(batch);

        orderIds.forEach(orderId -> {
            RiderDeliveryBatchOrder link = new RiderDeliveryBatchOrder();
            link.setBatchId(saved.getId());
            link.setOrderId(orderId);
            batchOrderRepository.save(link);
        });
        return saved;
    }
}
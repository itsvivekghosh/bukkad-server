package com.bhukkad.delivery.service;

import com.bhukkad.delivery.client.PaymentServiceClient;
import com.bhukkad.delivery.domain.RiderDeliveryBatch;
import com.bhukkad.delivery.domain.RiderDeliveryBatchOrder;
import com.bhukkad.delivery.domain.RiderDeliveryBatchOrderRepository;
import com.bhukkad.delivery.domain.RiderDeliveryBatchRepository;
import com.bhukkad.delivery.domain.RiderLocationUpdate;
import com.bhukkad.delivery.domain.RiderLocationUpdateRepository;
import com.bhukkad.delivery.RiderEarningsProperties;
import com.bhukkad.common.error.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Rider real-time ops (Priority 5): location pings, COD wallet, batch dispatch.
 * COD wallet and rider earnings are delegated to the payment service.
 */
@Service
@RequiredArgsConstructor
public class RiderOpsService {

    private final RiderLocationUpdateRepository locationRepository;
    private final RiderDeliveryBatchRepository batchRepository;
    private final RiderDeliveryBatchOrderRepository batchOrderRepository;
    private final PaymentServiceClient paymentClient;
    private final RiderEarningsProperties earningsProperties;

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

    /**
     * Credits COD wallet via payment service.
     */
    public Map<String, Object> creditCod(Long agentId, BigDecimal amount) {
        if (amount.signum() <= 0) {
            throw new BusinessException("COD credit must be positive");
        }
        return paymentClient.creditCodWallet(agentId, amount);
    }

    /**
     * Gets COD wallet balance via payment service.
     */
    public Map<String, Object> getCodWallet(Long agentId) {
        return paymentClient.getCodWallet(agentId);
    }

    /**
     * Debits COD wallet via payment service. Used when a rider's settlement
     * reverses a prior credit (e.g. returned order with COD refund reversal).
     */
    public Map<String, Object> debitCod(Long agentId, BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new BusinessException("COD debit amount must be positive");
        }
        return paymentClient.debitCodWallet(agentId, amount);
    }

    /**
     * Records rider earnings for an order via payment service.
     * Defaults to the configured {@code perDelivery} amount when none supplied.
     */
    public Map<String, Object> recordEarning(Long agentId, Long orderId) {
        BigDecimal amount = BigDecimal.valueOf(earningsProperties.getPerDelivery());
        return paymentClient.recordEarning(agentId, orderId, amount);
    }

    /**
     * Retrieves all earnings recorded for the given rider via payment service.
     */
    public List<Map<String, Object>> getEarnings(Long agentId) {
        return paymentClient.getEarnings(agentId);
    }

    /**
     * Marks a recorded earning as paid (flushed to rider's COD wallet)
     * via payment service.
     */
    public Map<String, Object> markEarningPaid(Long earningId) {
        return paymentClient.markEarningPaid(earningId);
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

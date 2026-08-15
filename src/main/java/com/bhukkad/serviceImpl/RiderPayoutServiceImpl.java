package com.bhukkad.serviceImpl;

import com.bhukkad.config.RiderEarningsProperties;
import com.bhukkad.dto.response.PagedResponse;
import com.bhukkad.dto.response.RiderEarningsSummaryResponse;
import com.bhukkad.dto.response.RiderPayoutResponse;
import com.bhukkad.entity.DeliveryAgent;
import com.bhukkad.entity.RiderEarning;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.repository.DeliveryAgentRepository;
import com.bhukkad.repository.RiderEarningRepository;
import com.bhukkad.security.SecurityUtils;
import com.bhukkad.service.DeliveryService;
import com.bhukkad.service.RiderPayoutService;
import com.bhukkad.util.PaginationUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RiderPayoutServiceImpl implements RiderPayoutService {

    private final RiderEarningRepository riderEarningRepository;
    private final SecurityUtils securityUtils;
    private final DeliveryService deliveryService;
    private final RiderEarningsProperties riderEarningsProperties;
    private final DeliveryAgentRepository deliveryAgentRepository;

    @Override
    public RiderEarningsSummaryResponse getEarningsSummary() {
        Long agentId = securityUtils.getCurrentUserId();
        Double pending = riderEarningRepository.sumAmountByAgentIdAndStatus(agentId, RiderEarning.EarningStatus.PENDING);
        Double paid = riderEarningRepository.sumAmountByAgentIdAndStatus(agentId, RiderEarning.EarningStatus.PAID);
        return RiderEarningsSummaryResponse.builder()
                .pendingAmount(pending != null ? pending : 0.0)
                .paidAmount(paid != null ? paid : 0.0)
                .perDeliveryFee(riderEarningsProperties.getPerDelivery())
                .totalDeliveries((long) deliveryService.getCurrentDeliveryAgent().getTotalDeliveries())
                .build();
    }

    @Override
    public PagedResponse<RiderPayoutResponse> getPayoutHistory(int page, int size) {
        Long agentId = securityUtils.getCurrentUserId();
        var earningsPage = riderEarningRepository.findByAgentIdOrderByCreatedAtDesc(
                agentId,
                PaginationUtils.page(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
        return PagedResponse.from(earningsPage.map(this::mapToResponse));
    }

    @Override
    @Transactional
    public int settlePendingPayouts(Long agentId) {
        DeliveryAgent agent = deliveryAgentRepository.findById(agentId)
                .orElseThrow(() -> new ResourceNotFoundException("Delivery agent not found"));
        var pending = riderEarningRepository.findByAgentIdAndStatus(agent.getId(), RiderEarning.EarningStatus.PENDING);
        var now = java.time.LocalDateTime.now();
        for (RiderEarning earning : pending) {
            earning.setStatus(RiderEarning.EarningStatus.PAID);
            earning.setPaidAt(now);
        }
        riderEarningRepository.saveAll(pending);
        return pending.size();
    }

    private RiderPayoutResponse mapToResponse(RiderEarning earning) {
        return RiderPayoutResponse.builder()
                .id(earning.getId())
                .orderId(earning.getOrder().getId())
                .orderNumber(earning.getOrder().getOrderNumber())
                .amount(earning.getAmount())
                .status(earning.getStatus().name())
                .createdAt(earning.getCreatedAt() != null ? earning.getCreatedAt().toString() : null)
                .paidAt(earning.getPaidAt() != null ? earning.getPaidAt().toString() : null)
                .build();
    }
}

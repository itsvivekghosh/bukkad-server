package com.bhukkad.settlement;

import com.bhukkad.config.SettlementProperties;
import com.bhukkad.entity.Restaurant;
import com.bhukkad.entity.RiderEarning;
import com.bhukkad.entity.SettlementRun;
import com.bhukkad.repository.DeliveryAgentRepository;
import com.bhukkad.repository.RestaurantRepository;
import com.bhukkad.repository.RiderEarningRepository;
import com.bhukkad.repository.SettlementRunRepository;
import com.bhukkad.service.RiderPayoutService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Automated settlement batch processor for restaurants and riders (V16).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SettlementAutomationScheduler {

    private final SettlementProperties settlementProperties;
    private final RestaurantSettlementService restaurantSettlementService;
    private final RiderPayoutService riderPayoutService;
    private final RestaurantRepository restaurantRepository;
    private final DeliveryAgentRepository deliveryAgentRepository;
    private final RiderEarningRepository riderEarningRepository;
    private final SettlementRunRepository settlementRunRepository;

    private static final int SETTLEMENT_BATCH = 100;

    @Scheduled(cron = "${app.settlement.auto-settle-cron:0 0 2 * * *}")
    @SchedulerLock(name = "settlement-automation", lockAtMostFor = "PT30M", lockAtLeastFor = "PT5M")
    @Transactional
    public void runAutomatedSettlement() {
        if (!settlementProperties.isAutoSettleEnabled()) {
            return;
        }

        SettlementRun run = new SettlementRun();
        run.setRunType("AUTOMATED");
        run.setStatus(SettlementRun.RunStatus.RUNNING);
        run.setStartedAt(LocalDateTime.now());
        settlementRunRepository.save(run);

        int restaurantsSettled = 0;
        int agentsSettled = 0;
        double totalAmount = 0.0;

        try {
            int page = 0;
            Page<Restaurant> batch;
            do {
                batch = restaurantRepository.findAll(PageRequest.of(page, SETTLEMENT_BATCH, Sort.by("id")));
                for (Restaurant restaurant : batch.getContent()) {
                    double pending = restaurantSettlementService.getPendingSettlementAmount(restaurant.getId());
                    if (pending >= settlementProperties.getMinPendingAmount()) {
                        int count = restaurantSettlementService.settlePendingForRestaurant(restaurant.getId());
                        if (count > 0) {
                            restaurantsSettled++;
                            totalAmount += pending;
                        }
                    }
                }
                page++;
                if (batch.hasNext()) {
                    try { Thread.sleep(50); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                }
            } while (batch.hasNext());

            int agentPage = 0;
            Page<com.bhukkad.entity.DeliveryAgent> agentBatch;
            do {
                agentBatch = deliveryAgentRepository.findAll(PageRequest.of(agentPage, SETTLEMENT_BATCH, Sort.by("id")));
                for (var agent : agentBatch.getContent()) {
                    var pendingEarnings = riderEarningRepository.findByAgentIdAndStatus(
                            agent.getId(), RiderEarning.EarningStatus.PENDING);
                    double pending = pendingEarnings.stream().mapToDouble(RiderEarning::getAmount).sum();
                    if (pending >= settlementProperties.getMinPendingAmount()) {
                        int count = riderPayoutService.settlePendingPayouts(agent.getId());
                        if (count > 0) {
                            agentsSettled++;
                            totalAmount += pending;
                        }
                    }
                }
                agentPage++;
                if (agentBatch.hasNext()) {
                    try { Thread.sleep(50); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                }
            } while (agentBatch.hasNext());

            run.setRestaurantsSettled(restaurantsSettled);
            run.setAgentsSettled(agentsSettled);
            run.setTotalAmount(totalAmount);
            run.setStatus(SettlementRun.RunStatus.COMPLETED);
            run.setCompletedAt(LocalDateTime.now());
            run.setNotes(String.format("Settled %d restaurants, %d agents", restaurantsSettled, agentsSettled));
            log.info("Automated settlement completed | restaurants={} | agents={} | amount={}",
                    restaurantsSettled, agentsSettled, totalAmount);
        } catch (Exception ex) {
            run.setStatus(SettlementRun.RunStatus.FAILED);
            run.setCompletedAt(LocalDateTime.now());
            run.setNotes(ex.getMessage());
            log.error("Automated settlement failed", ex);
        }
        settlementRunRepository.save(run);
    }

    /**
     * Triggers an on-demand settlement run (admin).
     */
    @Transactional
    public SettlementRun triggerManualRun() {
        runAutomatedSettlement();
        return settlementRunRepository.findTop10ByOrderByStartedAtDesc().stream()
                .findFirst()
                .orElseThrow();
    }
}

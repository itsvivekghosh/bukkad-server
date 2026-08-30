package com.bhukkad.settlement;

import com.bhukkad.config.SettlementProperties;
import com.bhukkad.entity.DeliveryAgent;
import com.bhukkad.entity.Restaurant;
import com.bhukkad.entity.RiderEarning;
import com.bhukkad.entity.SettlementRun;
import com.bhukkad.repository.DeliveryAgentRepository;
import com.bhukkad.repository.RestaurantRepository;
import com.bhukkad.repository.RiderEarningRepository;
import com.bhukkad.repository.SettlementRunRepository;
import com.bhukkad.service.RiderPayoutService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.NoSuchElementException;

import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SettlementAutomationSchedulerTest {

    @Mock
    private SettlementProperties settlementProperties;
    @Mock
    private RestaurantSettlementService restaurantSettlementService;
    @Mock
    private RiderPayoutService riderPayoutService;
    @Mock
    private RestaurantRepository restaurantRepository;
    @Mock
    private DeliveryAgentRepository deliveryAgentRepository;
    @Mock
    private RiderEarningRepository riderEarningRepository;
    @Mock
    private SettlementRunRepository settlementRunRepository;

    @InjectMocks
    private SettlementAutomationScheduler scheduler;

    private Restaurant restaurant(Long id) {
        Restaurant restaurant = new Restaurant();
        restaurant.setId(id);
        return restaurant;
    }

    private DeliveryAgent agent(Long id) {
        DeliveryAgent agent = new DeliveryAgent();
        agent.setId(id);
        return agent;
    }

    private RiderEarning earning(double amount) {
        RiderEarning earning = new RiderEarning();
        earning.setAmount(amount);
        earning.setStatus(RiderEarning.EarningStatus.PENDING);
        return earning;
    }

    private SettlementRun lastSavedRun() {
        ArgumentCaptor<SettlementRun> captor = ArgumentCaptor.forClass(SettlementRun.class);
        verify(settlementRunRepository, times(2)).save(captor.capture());
        List<SettlementRun> runs = captor.getAllValues();
        return runs.get(runs.size() - 1);
    }

    @Test
    void runAutomatedSettlement_disabled_doesNothing() {
        when(settlementProperties.isAutoSettleEnabled()).thenReturn(false);

        scheduler.runAutomatedSettlement();

        verify(settlementRunRepository, never()).save(any(SettlementRun.class));
        verify(restaurantRepository, never()).findAll(any(Pageable.class));
        verify(deliveryAgentRepository, never()).findAll(any(Pageable.class));
    }

    @Test
    void runAutomatedSettlement_settlesEligibleRestaurantsAndAgents() {
        when(settlementProperties.isAutoSettleEnabled()).thenReturn(true);
        when(settlementProperties.getMinPendingAmount()).thenReturn(100.0);

        Restaurant eligible = restaurant(1L);
        Restaurant belowMin = restaurant(2L);
        Restaurant zeroCount = restaurant(3L);
        // Batch B: the scheduler pages through the fleet instead of loading it whole.
        when(restaurantRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(eligible, belowMin, zeroCount)));

        when(restaurantSettlementService.getPendingSettlementAmount(1L)).thenReturn(250.0);
        when(restaurantSettlementService.getPendingSettlementAmount(2L)).thenReturn(50.0);
        when(restaurantSettlementService.getPendingSettlementAmount(3L)).thenReturn(400.0);
        when(restaurantSettlementService.settlePendingForRestaurant(1L)).thenReturn(3);
        when(restaurantSettlementService.settlePendingForRestaurant(3L)).thenReturn(0);

        DeliveryAgent agentEligible = agent(10L);
        DeliveryAgent agentBelowMin = agent(11L);
        when(deliveryAgentRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(agentEligible, agentBelowMin)));
        when(riderEarningRepository.findByAgentIdAndStatus(10L, RiderEarning.EarningStatus.PENDING))
                .thenReturn(List.of(earning(120.0), earning(80.0)));
        when(riderEarningRepository.findByAgentIdAndStatus(11L, RiderEarning.EarningStatus.PENDING))
                .thenReturn(List.of(earning(20.0)));
        when(riderPayoutService.settlePendingPayouts(10L)).thenReturn(2);

        List<SettlementRun.RunStatus> savedStatuses = new java.util.ArrayList<>();
        when(settlementRunRepository.save(any(SettlementRun.class))).thenAnswer(invocation -> {
            savedStatuses.add(invocation.<SettlementRun>getArgument(0).getStatus());
            return invocation.getArgument(0);
        });

        scheduler.runAutomatedSettlement();

        SettlementRun run = lastSavedRun();
        assertEquals(SettlementRun.RunStatus.COMPLETED, run.getStatus());
        assertEquals("AUTOMATED", run.getRunType());
        assertEquals(1, run.getRestaurantsSettled());
        assertEquals(1, run.getAgentsSettled());
        assertEquals(450.0, run.getTotalAmount());
        assertEquals("Settled 1 restaurants, 1 agents", run.getNotes());
        assertNotNull(run.getStartedAt());
        assertNotNull(run.getCompletedAt());
        assertEquals(List.of(SettlementRun.RunStatus.RUNNING, SettlementRun.RunStatus.COMPLETED),
                savedStatuses);

        verify(restaurantSettlementService, never()).settlePendingForRestaurant(2L);
        verify(riderPayoutService, never()).settlePendingPayouts(11L);
    }

    @Test
    void runAutomatedSettlement_noRestaurantsOrAgents_completesWithZeros() {
        when(settlementProperties.isAutoSettleEnabled()).thenReturn(true);
        when(restaurantRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));
        when(deliveryAgentRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        scheduler.runAutomatedSettlement();

        SettlementRun run = lastSavedRun();
        assertEquals(SettlementRun.RunStatus.COMPLETED, run.getStatus());
        assertEquals(0, run.getRestaurantsSettled());
        assertEquals(0, run.getAgentsSettled());
        assertEquals(0.0, run.getTotalAmount());
    }

    @Test
    void runAutomatedSettlement_exception_marksRunFailed() {
        when(settlementProperties.isAutoSettleEnabled()).thenReturn(true);
        when(restaurantRepository.findAll(any(Pageable.class))).thenThrow(new RuntimeException("redis down"));

        scheduler.runAutomatedSettlement();

        SettlementRun run = lastSavedRun();
        assertEquals(SettlementRun.RunStatus.FAILED, run.getStatus());
        assertEquals("redis down", run.getNotes());
        assertNotNull(run.getCompletedAt());
    }

    @Test
    void triggerManualRun_disabled_returnsLatestRun() {
        when(settlementProperties.isAutoSettleEnabled()).thenReturn(false);
        SettlementRun latest = new SettlementRun();
        latest.setId(1L);
        latest.setRunType("AUTOMATED");
        latest.setStatus(SettlementRun.RunStatus.COMPLETED);
        when(settlementRunRepository.findTop10ByOrderByStartedAtDesc()).thenReturn(List.of(latest));

        SettlementRun result = scheduler.triggerManualRun();

        assertEquals(1L, result.getId());
        verify(settlementRunRepository, never()).save(any(SettlementRun.class));
    }

    @Test
    void triggerManualRun_enabled_returnsLatestRun() {
        when(settlementProperties.isAutoSettleEnabled()).thenReturn(true);
        when(restaurantRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));
        when(deliveryAgentRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        SettlementRun latest = new SettlementRun();
        latest.setId(9L);
        latest.setRunType("AUTOMATED");
        latest.setStatus(SettlementRun.RunStatus.COMPLETED);
        when(settlementRunRepository.findTop10ByOrderByStartedAtDesc()).thenReturn(List.of(latest));

        SettlementRun result = scheduler.triggerManualRun();

        assertEquals(9L, result.getId());
        verify(settlementRunRepository, times(2)).save(any(SettlementRun.class));
    }

    @Test
    void triggerManualRun_emptyHistory_throws() {
        when(settlementProperties.isAutoSettleEnabled()).thenReturn(false);
        when(settlementRunRepository.findTop10ByOrderByStartedAtDesc()).thenReturn(List.of());

        assertThrows(NoSuchElementException.class, () -> scheduler.triggerManualRun());
    }
}

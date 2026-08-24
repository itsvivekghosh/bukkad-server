package com.bhukkad.delivery;

import com.bhukkad.config.RiderEarningsProperties;
import com.bhukkad.entity.DeliveryAgent;
import com.bhukkad.entity.Order;
import com.bhukkad.entity.RiderEarning;
import com.bhukkad.repository.RiderEarningRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RiderEarningServiceTest {

    private static final Long ORDER_ID = 42L;
    private static final double DEFAULT_PER_DELIVERY = 30.0;

    @Mock
    private RiderEarningRepository riderEarningRepository;

    private RiderEarningsProperties riderEarningsProperties;
    private RiderEarningService service;

    @BeforeEach
    void setUp() {
        riderEarningsProperties = new RiderEarningsProperties();
        // Constructor parameter order mirrors field declaration order in the source.
        service = new RiderEarningService(riderEarningRepository, riderEarningsProperties);
    }

    private Order order(Double tipAmount) {
        Order order = new Order();
        order.setId(ORDER_ID);
        order.setTipAmount(tipAmount);
        return order;
    }

    @Test
    void recordDeliveryEarning_earningAlreadyRecorded_skipsSave() {
        Order order = order(10.0);
        when(riderEarningRepository.existsByOrderId(ORDER_ID)).thenReturn(true);

        service.recordDeliveryEarning(order, new DeliveryAgent());

        verify(riderEarningRepository).existsByOrderId(ORDER_ID);
        verify(riderEarningRepository, never()).save(any(RiderEarning.class));
        verifyNoMoreInteractions(riderEarningRepository);
    }

    @Test
    void recordDeliveryEarning_nullTip_savesPendingEarningWithBaseAmountOnly() {
        Order order = order(null);
        DeliveryAgent agent = new DeliveryAgent();
        when(riderEarningRepository.existsByOrderId(ORDER_ID)).thenReturn(false);

        service.recordDeliveryEarning(order, agent);

        ArgumentCaptor<RiderEarning> captor = ArgumentCaptor.forClass(RiderEarning.class);
        verify(riderEarningRepository).save(captor.capture());

        RiderEarning saved = captor.getValue();
        assertSame(agent, saved.getAgent());
        assertSame(order, saved.getOrder());
        assertEquals(DEFAULT_PER_DELIVERY, saved.getAmount());
        assertEquals(RiderEarning.EarningStatus.PENDING, saved.getStatus());
    }

    @Test
    void recordDeliveryEarning_zeroTip_savesBaseAmountUnchanged() {
        Order order = order(0.0);
        when(riderEarningRepository.existsByOrderId(ORDER_ID)).thenReturn(false);

        service.recordDeliveryEarning(order, new DeliveryAgent());

        ArgumentCaptor<RiderEarning> captor = ArgumentCaptor.forClass(RiderEarning.class);
        verify(riderEarningRepository).save(captor.capture());
        assertEquals(DEFAULT_PER_DELIVERY, captor.getValue().getAmount());
    }

    @Test
    void recordDeliveryEarning_tipPresent_roundsSumToTwoDecimals() {
        riderEarningsProperties.setPerDelivery(10.0);
        // 10.0 + 5.125 = 15.125 exactly representable in binary, so rounding is deterministic.
        Order order = order(5.125);
        when(riderEarningRepository.existsByOrderId(ORDER_ID)).thenReturn(false);

        service.recordDeliveryEarning(order, new DeliveryAgent());

        ArgumentCaptor<RiderEarning> captor = ArgumentCaptor.forClass(RiderEarning.class);
        verify(riderEarningRepository).save(captor.capture());
        assertEquals(15.13, captor.getValue().getAmount());
        assertEquals(RiderEarning.EarningStatus.PENDING, captor.getValue().getStatus());
    }

    @Test
    void recordDeliveryEarning_tipWithExactTwoDecimals_amountStoredWithoutRoundingDrift() {
        Order order = order(19.25); // exact binary fraction, 30.0 + 19.25 = 49.25
        when(riderEarningRepository.existsByOrderId(ORDER_ID)).thenReturn(false);

        service.recordDeliveryEarning(order, new DeliveryAgent());

        ArgumentCaptor<RiderEarning> captor = ArgumentCaptor.forClass(RiderEarning.class);
        verify(riderEarningRepository).save(captor.capture());
        assertNotNull(captor.getValue());
        assertEquals(49.25, captor.getValue().getAmount());
    }
}

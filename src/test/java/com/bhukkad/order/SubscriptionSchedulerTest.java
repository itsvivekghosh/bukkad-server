package com.bhukkad.order;

import com.bhukkad.config.SubscriptionProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SubscriptionSchedulerTest {

    @Mock
    private SubscriptionService subscriptionService;

    private final SubscriptionProperties properties = new SubscriptionProperties();

    @Test
    void materialize_skipsWhenDisabled() {
        properties.setEnabled(false);
        SubscriptionScheduler scheduler = new SubscriptionScheduler(properties, subscriptionService);

        scheduler.materialize();

        verify(subscriptionService, never()).materializeDue();
    }

    @Test
    void materialize_delegatesWhenEnabled() {
        properties.setEnabled(true);
        when(subscriptionService.materializeDue()).thenReturn(2);
        SubscriptionScheduler scheduler = new SubscriptionScheduler(properties, subscriptionService);

        scheduler.materialize();

        verify(subscriptionService).materializeDue();
    }

    @Test
    void materialize_handlesZeroDue() {
        properties.setEnabled(true);
        when(subscriptionService.materializeDue()).thenReturn(0);
        SubscriptionScheduler scheduler = new SubscriptionScheduler(properties, subscriptionService);

        scheduler.materialize();

        verify(subscriptionService).materializeDue();
    }
}

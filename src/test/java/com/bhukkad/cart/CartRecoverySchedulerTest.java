package com.bhukkad.cart;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CartRecoverySchedulerTest {

    @Mock
    private CartRecoveryProperties properties;
    @Mock
    private CartRecoveryService cartRecoveryService;

    @Test
    void run_disabled_doesNotDelegateToService() {
        CartRecoveryScheduler scheduler = new CartRecoveryScheduler(properties, cartRecoveryService);
        when(properties.isEnabled()).thenReturn(false);

        scheduler.run();

        verify(cartRecoveryService, never()).recoverAbandonedCarts();
    }

    @Test
    void run_enabled_delegatesToService() {
        CartRecoveryScheduler scheduler = new CartRecoveryScheduler(properties, cartRecoveryService);
        when(properties.isEnabled()).thenReturn(true);

        scheduler.run();

        verify(cartRecoveryService).recoverAbandonedCarts();
    }
}

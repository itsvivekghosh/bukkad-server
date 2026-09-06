package com.bhukkad.delivery.live;

import com.bhukkad.delivery.dto.response.OrderLiveUpdate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;

/**
 * Single-node relay: publish goes straight to the local dispatcher.
 */
@ExtendWith(MockitoExtension.class)
class LocalOrderLiveRelayTest {

    @Mock private OrderLiveLocalDispatcher localDispatcher;
    @InjectMocks private LocalOrderLiveRelay relay;

    @Test
    void publish_delegatesToLocalDispatcher() {
        OrderLiveUpdate update = OrderLiveUpdate.builder().orderId(42L).build();

        relay.publish(update);

        verify(localDispatcher).dispatch(update);
    }
}

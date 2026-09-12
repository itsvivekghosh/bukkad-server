package com.bhukkad.delivery.live;

import com.bhukkad.delivery.dto.response.OrderLiveUpdate;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.cluster.live-relay", name = "enabled", havingValue = "false", matchIfMissing = true)
public class LocalOrderLiveRelay implements OrderLiveRelay {

    private final OrderLiveLocalDispatcher localDispatcher;

    @Override
    public void publish(OrderLiveUpdate update) {
        localDispatcher.dispatch(update);
    }
}

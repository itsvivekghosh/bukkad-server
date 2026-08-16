package com.bhukkad.live;

import com.bhukkad.dto.response.OrderLiveUpdate;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.cluster.live-relay", name = "enabled", havingValue = "false")
public class LocalOrderLiveRelay implements OrderLiveRelay {

    private final OrderLiveLocalDispatcher localDispatcher;

    @Override
    public void publish(OrderLiveUpdate update) {
        localDispatcher.dispatch(update);
    }
}

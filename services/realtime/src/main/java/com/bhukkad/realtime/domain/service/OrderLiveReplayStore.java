package com.bhukkad.realtime.domain.service;

import com.bhukkad.realtime.domain.event.OrderLiveUpdate;
import java.util.List;

public interface OrderLiveReplayStore {

    long nextEventId();

    void record(OrderLiveUpdate update);

    List<OrderLiveUpdate> replayAfter(String streamKey, long lastEventId);

    String streamKeyKitchen(Long restaurantId);

    String streamKeyOrder(Long orderId);

    String streamKeyRider(Long agentId);

    long parseLastEventId(String lastEventId);
}

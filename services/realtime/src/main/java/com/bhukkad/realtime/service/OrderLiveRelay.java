package com.bhukkad.realtime.service;

import com.bhukkad.realtime.dto.LiveUpdateEvent;
import com.bhukkad.realtime.dto.OrderLiveUpdate;
import java.util.List;

public interface OrderLiveRelay {

    void publish(OrderLiveUpdate update);

    /**
     * Ingests a platform-derived live update (strangler equivalent of the
     * monolith's {@code OrderLiveUpdateBroadcaster.dispatch}): assigns a
     * monotonic event id, records it for reconnect replay, then fans it out
     * to the per-stream relay channels (kitchen / order / rider).
     *
     * @param event envelope whose {@code payload} carries an {@link OrderLiveUpdate}
     */
    void relay(LiveUpdateEvent event);

    void subscribe(String topic, java.util.function.Consumer<OrderLiveUpdate> consumer);

    List<OrderLiveUpdate> replayAfter(String streamKey, long lastEventId);
}

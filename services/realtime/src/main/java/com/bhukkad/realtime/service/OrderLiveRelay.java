package com.bhukkad.realtime.service;

import com.bhukkad.realtime.dto.LiveUpdateEvent;
import com.bhukkad.realtime.dto.OrderLiveUpdate;
import java.util.List;

public interface OrderLiveRelay {

    void publish(OrderLiveUpdate update);

    /**
     * Subscribes to every relay channel and hands each received update to
     * {@code sink} together with the channel it arrived on. Called once at
     * startup by the SSE bridge; an update fanned to several channels must be
     * routed by channel (otherwise kitchen clients receive it once per
     * channel).
     */
    default void subscribeAll(java.util.function.BiConsumer<String, OrderLiveUpdate> sink) {
    }

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

    /**
     * Removes a consumer registered via {@link #subscribe}. Callers MUST pair
     * every subscribe with an unsubscribe when their stream ends (SSE
     * completion/timeout/error callbacks), otherwise the relay's per-topic
     * consumer lists grow for the pod lifetime (audit V-07 memory leak:
     * {@code RedisOrderLiveRelay.localConsumers}). Removing the last consumer
     * of a topic also tears down that topic's Redis listener.
     */
    default void unsubscribe(String topic, java.util.function.Consumer<OrderLiveUpdate> consumer) {
    }

    List<OrderLiveUpdate> replayAfter(String streamKey, long lastEventId);
}

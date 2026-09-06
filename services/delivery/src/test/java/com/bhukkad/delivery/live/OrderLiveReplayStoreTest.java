package com.bhukkad.delivery.live;

import com.bhukkad.delivery.dto.response.OrderLiveUpdate;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Redis sorted-set replay buffer: monotonic event ids, fan-out appends per
 * audience stream, trimming at the per-stream cap, and after-id reads.
 */
@ExtendWith(MockitoExtension.class)
class OrderLiveReplayStoreTest {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;
    @Mock private ZSetOperations<String, String> zSetOps;

    private OrderLiveReplayStore storeWith(OrderLiveReplayProperties properties) {
        return new OrderLiveReplayStore(redisTemplate, objectMapper, properties);
    }

    @Test
    void nextEventId_returnsRedisIncrement() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(OrderLiveReplayStore.EVENT_ID_SEQUENCE_KEY)).thenReturn(99L);

        assertThat(storeWith(new OrderLiveReplayProperties()).nextEventId()).isEqualTo(99L);
    }

    @Test
    void nextEventId_fallsBackToNanoTimeWhenRedisReturnsNull() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(OrderLiveReplayStore.EVENT_ID_SEQUENCE_KEY)).thenReturn(null);

        assertThat(storeWith(new OrderLiveReplayProperties()).nextEventId()).isPositive();
    }

    @Test
    void record_nullUpdateIgnored() {
        storeWith(new OrderLiveReplayProperties()).record(null);

        verifyNoInteractions(redisTemplate);
    }

    @Test
    void record_missingEventIdIgnored() {
        storeWith(new OrderLiveReplayProperties()).record(OrderLiveUpdate.builder().orderId(1L).build());

        verifyNoInteractions(redisTemplate);
    }

    @Test
    void record_appendsToAllThreeStreamsAndSetsTtl() {
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        when(zSetOps.size(anyString())).thenReturn(1L);
        OrderLiveReplayStore store = storeWith(new OrderLiveReplayProperties());
        OrderLiveUpdate update = OrderLiveUpdate.builder()
                .eventId(5L).orderId(7L).restaurantId(9L).deliveryAgentId(3L)
                .changedAt(LocalDateTime.of(2026, 9, 5, 12, 0))
                .build();

        store.record(update);

        String payload = serialize(update);
        verify(zSetOps).add("live:replay:kitchen:9", payload, 5L);
        verify(zSetOps).add("live:replay:order:7", payload, 5L);
        verify(zSetOps).add("live:replay:rider:3", payload, 5L);
        verify(redisTemplate).expire(eq("live:replay:kitchen:9"), eq(Duration.ofSeconds(3600)));
    }

    @Test
    void record_skipsStreamsForMissingScopeIds() {
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        when(zSetOps.size(anyString())).thenReturn(1L);
        OrderLiveReplayStore store = storeWith(new OrderLiveReplayProperties());

        store.record(OrderLiveUpdate.builder().eventId(5L).orderId(7L).build());

        verify(zSetOps).add(eq("live:replay:order:7"), anyString(), eq(5D));
        verify(zSetOps, never()).add(eq("live:replay:rider:3"), anyString(), anyDouble());
    }

    @Test
    void record_trimsOldestWhenOverCapacity() {
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        when(zSetOps.size(anyString())).thenReturn(4L);
        OrderLiveReplayProperties properties = new OrderLiveReplayProperties();
        properties.setMaxEventsPerStream(2);
        OrderLiveReplayStore store = storeWith(properties);

        store.record(OrderLiveUpdate.builder().eventId(5L).restaurantId(9L).build());

        verify(zSetOps).removeRange("live:replay:kitchen:9", 0, 1);
    }

    @Test
    void record_noTrimWhenWithinCapacity() {
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        when(zSetOps.size(anyString())).thenReturn(2L);
        OrderLiveReplayProperties properties = new OrderLiveReplayProperties();
        properties.setMaxEventsPerStream(2);
        OrderLiveReplayStore store = storeWith(properties);

        store.record(OrderLiveUpdate.builder().eventId(5L).restaurantId(9L).build());

        verify(zSetOps, never()).removeRange(anyString(), anyLong(), anyLong());
    }

    @Test
    void record_serializationFailureOnlyLogs() throws Exception {
        ObjectMapper faulty = mock(ObjectMapper.class);
        when(faulty.writeValueAsString(any())).thenThrow(new JsonProcessingException("boom") {
        });
        OrderLiveReplayStore store =
                new OrderLiveReplayStore(redisTemplate, faulty, new OrderLiveReplayProperties());

        store.record(OrderLiveUpdate.builder().eventId(1L).restaurantId(9L).build());

        verifyNoInteractions(redisTemplate);
    }

    @Test
    void replayAfter_blankKeyReturnsEmpty() {
        List<OrderLiveUpdate> result = storeWith(new OrderLiveReplayProperties()).replayAfter(" ", 3L);

        assertThat(result).isEmpty();
        verifyNoInteractions(redisTemplate);
    }

    @Test
    void replayAfter_negativeCursorReturnsEmpty() {
        assertThat(storeWith(new OrderLiveReplayProperties()).replayAfter("kitchen:9", -2L)).isEmpty();
        verifyNoInteractions(redisTemplate);
    }

    @Test
    void replayAfter_emptyResultSetsReturnEmpty() {
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        when(zSetOps.rangeByScore("live:replay:kitchen:9", 6D, Double.MAX_VALUE)).thenReturn(null);
        OrderLiveReplayStore store = storeWith(new OrderLiveReplayProperties());

        assertThat(store.replayAfter("kitchen:9", 5L)).isEmpty();

        when(zSetOps.rangeByScore("live:replay:kitchen:8", 6D, Double.MAX_VALUE))
                .thenReturn(Set.of());
        assertThat(store.replayAfter("kitchen:8", 5L)).isEmpty();
    }

    @Test
    void replayAfter_deserializesEntriesAfterCursor() throws Exception {
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        OrderLiveUpdate stored = OrderLiveUpdate.builder().eventId(8L).orderId(7L).build();
        Set<String> payloads = new LinkedHashSet<>(List.of(objectMapper.writeValueAsString(stored)));
        when(zSetOps.rangeByScore("live:replay:kitchen:9", 6D, Double.MAX_VALUE)).thenReturn(payloads);
        OrderLiveReplayStore store = storeWith(new OrderLiveReplayProperties());

        List<OrderLiveUpdate> result = store.replayAfter("kitchen:9", 5L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getEventId()).isEqualTo(8L);
        assertThat(result.get(0).getOrderId()).isEqualTo(7L);
    }

    @Test
    void replayAfter_redisFailureReturnsEmpty() {
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        when(zSetOps.rangeByScore(anyString(), anyDouble(), anyDouble()))
                .thenThrow(new RuntimeException("redis down"));
        OrderLiveReplayStore store = storeWith(new OrderLiveReplayProperties());

        assertThat(store.replayAfter("kitchen:9", 5L)).isEmpty();
    }

    @Test
    void streamKeyHelpers_encodeAudienceScope() {
        assertThat(OrderLiveReplayStore.streamKeyKitchen(9L)).isEqualTo("kitchen:9");
        assertThat(OrderLiveReplayStore.streamKeyKitchen(null)).isNull();
        assertThat(OrderLiveReplayStore.streamKeyOrder(7L)).isEqualTo("order:7");
        assertThat(OrderLiveReplayStore.streamKeyOrder(null)).isNull();
        assertThat(OrderLiveReplayStore.streamKeyRider(3L)).isEqualTo("rider:3");
        assertThat(OrderLiveReplayStore.streamKeyRider(null)).isNull();
    }

    @Test
    void parseLastEventId_handlesBlankAndGarbage() {
        assertThat(OrderLiveReplayStore.parseLastEventId(null)).isEqualTo(-1L);
        assertThat(OrderLiveReplayStore.parseLastEventId("  ")).isEqualTo(-1L);
        assertThat(OrderLiveReplayStore.parseLastEventId("nope")).isEqualTo(-1L);
        assertThat(OrderLiveReplayStore.parseLastEventId(" 12 ")).isEqualTo(12L);
    }

    private String serialize(OrderLiveUpdate update) {
        try {
            return objectMapper.writeValueAsString(update);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}

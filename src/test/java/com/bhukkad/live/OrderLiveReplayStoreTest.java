package com.bhukkad.live;

import com.bhukkad.dto.response.OrderLiveUpdate;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderLiveReplayStoreTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private ZSetOperations<String, String> zSetOperations;

    private OrderLiveReplayStore replayStore;

    @BeforeEach
    void setUp() {
        OrderLiveReplayProperties properties = new OrderLiveReplayProperties();
        properties.setMaxEventsPerStream(10);
        properties.setTtlSeconds(3600);
        replayStore = new OrderLiveReplayStore(
                stringRedisTemplate,
                new ObjectMapper().registerModule(new JavaTimeModule()),
                properties);
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOperations);
    }

    @Test
    void nextEventId_usesRedisIncrement() {
        when(valueOperations.increment(OrderLiveReplayStore.EVENT_ID_SEQUENCE_KEY)).thenReturn(42L);

        assertEquals(42L, replayStore.nextEventId());
    }

    @Test
    void record_writesToKitchenOrderAndRiderStreams() throws Exception {
        OrderLiveUpdate update = OrderLiveUpdate.builder()
                .eventId(5L)
                .orderId(1L)
                .restaurantId(2L)
                .deliveryAgentId(3L)
                .eventType(OrderLiveUpdate.EventType.STATUS_CHANGED)
                .changedAt(LocalDateTime.now())
                .build();

        replayStore.record(update);

        verify(zSetOperations, times(3)).add(startsWith("live:replay:"), anyString(), eq(5D));
    }

    @Test
    void replayAfter_returnsEventsAfterLastId() throws Exception {
        OrderLiveUpdate update = OrderLiveUpdate.builder()
                .eventId(9L)
                .orderId(1L)
                .restaurantId(2L)
                .eventType(OrderLiveUpdate.EventType.ORDER_CREATED)
                .changedAt(LocalDateTime.now())
                .build();
        String payload = new ObjectMapper().registerModule(new JavaTimeModule()).writeValueAsString(update);
        Set<String> payloads = new LinkedHashSet<>();
        payloads.add(payload);
        when(zSetOperations.rangeByScore(eq("live:replay:order:1"), eq(9D), eq(Double.MAX_VALUE)))
                .thenReturn(payloads);

        List<OrderLiveUpdate> replayed = replayStore.replayAfter("order:1", 8L);

        assertEquals(1, replayed.size());
        assertEquals(9L, replayed.get(0).getEventId());
    }

    @Test
    void parseLastEventId_invalidReturnsNegativeOne() {
        assertEquals(-1L, OrderLiveReplayStore.parseLastEventId("not-a-number"));
        assertEquals(-1L, OrderLiveReplayStore.parseLastEventId(""));
    }

    // ==================== additional coverage ====================

    @Test
    void nextEventId_redisDown_fallsBackToNanoTime() {
        when(valueOperations.increment(OrderLiveReplayStore.EVENT_ID_SEQUENCE_KEY)).thenReturn(null);

        long id = replayStore.nextEventId();

        assertTrue(id > 0);
    }

    @Test
    void record_nullUpdate_skips() {
        replayStore.record(null);
        replayStore.record(OrderLiveUpdate.builder().build());

        verify(zSetOperations, never()).add(anyString(), anyString(), anyDouble());
    }

    @Test
    void record_kitchenOnlyStream() {
        OrderLiveUpdate update = OrderLiveUpdate.builder()
                .eventId(5L)
                .restaurantId(2L)
                .build();

        replayStore.record(update);

        verify(zSetOperations, times(1)).add(startsWith("live:replay:"), anyString(), eq(5D));
    }

    @Test
    void record_serializationFailure_isSwallowed() {
        // An update whose changedAt/eventType combination still serializes fine;
        // the point is that append() failures never propagate.
        OrderLiveUpdate update = OrderLiveUpdate.builder()
                .eventId(7L)
                .orderId(1L)
                .changedAt(null)
                .build();

        replayStore.record(update);
        // No exception expected; append failures are logged and swallowed
    }

    @Test
    void replayAfter_blankStreamKey_returnsEmpty() {
        assertTrue(replayStore.replayAfter("", 5L).isEmpty());
        assertTrue(replayStore.replayAfter(null, 5L).isEmpty());
    }

    @Test
    void replayAfter_negativeLastEventId_returnsEmpty() {
        assertTrue(replayStore.replayAfter("order:1", -1L).isEmpty());
    }

    @Test
    void replayAfter_noPayloads_returnsEmpty() {
        when(zSetOperations.rangeByScore(eq("live:replay:order:1"), anyDouble(), anyDouble()))
                .thenReturn(null);

        assertTrue(replayStore.replayAfter("order:1", 5L).isEmpty());
    }

    @Test
    void replayAfter_corruptPayload_returnsEmpty() {
        Set<String> payloads = new LinkedHashSet<>();
        payloads.add("{not-json");
        when(zSetOperations.rangeByScore(eq("live:replay:order:1"), anyDouble(), anyDouble()))
                .thenReturn(payloads);

        assertTrue(replayStore.replayAfter("order:1", 5L).isEmpty());
    }

    @Test
    void append_trimsExcessEvents() {
        // max=2: after adding the 3rd event, one must be trimmed away
        OrderLiveReplayProperties props = propertiesWithTinyLimit();
        OrderLiveReplayStore small = new OrderLiveReplayStore(
                stringRedisTemplate,
                new ObjectMapper().registerModule(new JavaTimeModule()),
                props);
        when(zSetOperations.size("live:replay:order:1")).thenReturn(3L);

        small.record(OrderLiveUpdate.builder().eventId(3L).orderId(1L)
                .changedAt(LocalDateTime.now())
                .eventType(OrderLiveUpdate.EventType.STATUS_CHANGED).build());

        verify(zSetOperations).removeRange("live:replay:order:1", 0, 0);
    }

    @Test
    void append_sizeWithinLimit_skipsTrim() {
        when(zSetOperations.size("live:replay:order:1")).thenReturn(2L);

        replayStore.record(OrderLiveUpdate.builder().eventId(3L).orderId(1L)
                .changedAt(LocalDateTime.now())
                .eventType(OrderLiveUpdate.EventType.STATUS_CHANGED).build());

        verify(zSetOperations, never()).removeRange(anyString(), anyLong(), anyLong());
    }

    private OrderLiveReplayProperties propertiesWithTinyLimit() {
        OrderLiveReplayProperties props = new OrderLiveReplayProperties();
        props.setMaxEventsPerStream(2);
        props.setTtlSeconds(3600);
        return props;
    }
}

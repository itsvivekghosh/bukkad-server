package com.bhukkad.live;

import com.bhukkad.dto.response.OrderLiveUpdate;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
}

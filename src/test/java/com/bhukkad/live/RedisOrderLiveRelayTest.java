package com.bhukkad.live;

import com.bhukkad.cluster.ClusterProperties;
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

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RedisOrderLiveRelayTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private RedisOrderLiveRelay relay;

    @BeforeEach
    void setUp() {
        ClusterProperties properties = new ClusterProperties();
        properties.getLiveRelay().setChannel("bhukkad:live:order-updates");
        relay = new RedisOrderLiveRelay(stringRedisTemplate, objectMapper, properties);
    }

    @Test
    void publish_sendsSerializedUpdateToRedisChannel() throws Exception {
        OrderLiveUpdate update = OrderLiveUpdate.builder()
                .eventType(OrderLiveUpdate.EventType.ORDER_CREATED)
                .orderId(42L)
                .restaurantId(3L)
                .changedAt(LocalDateTime.parse("2026-08-14T10:00:00"))
                .build();

        relay.publish(update);

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(stringRedisTemplate).convertAndSend(
                org.mockito.ArgumentMatchers.eq("bhukkad:live:order-updates"),
                payloadCaptor.capture());
        assertTrue(payloadCaptor.getValue().contains("\"orderId\":42"));
    }
}

package com.bhukkad.admin.domain.mapper;

import com.bhukkad.admin.api.dto.response.DeadLetterEventResponse;
import com.bhukkad.common.outbox.DeadLetterEvent;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class DeadLetterEventResponseMapperTest {

    @Test
    void utilityHasPrivateCtor() throws Exception {
        Constructor<DeadLetterEventResponseMapper> ctor =
                DeadLetterEventResponseMapper.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        assertThat(ctor.newInstance()).isNotNull();
    }

    @Test
    void from_mapsAllFields() {
        DeadLetterEvent event = new DeadLetterEvent();
        event.setId(9L);
        event.setEventType("order.created");
        event.setAggregateType("ORDER");
        event.setAggregateId(42L);
        event.setPayload("{\"orderId\":42}");
        event.setLastError("connection reset");
        event.setRetryCount(3);
        event.setSource("OUTBOX");
        event.setStatus(DeadLetterEvent.DlqStatus.REQUEUED);
        event.setCreatedAt(LocalDateTime.of(2026, 1, 1, 10, 0));
        event.setRequeuedAt(LocalDateTime.of(2026, 1, 2, 11, 0));

        DeadLetterEventResponse response = DeadLetterEventResponseMapper.from(event);

        assertThat(response.getId()).isEqualTo(9L);
        assertThat(response.getEventType()).isEqualTo("order.created");
        assertThat(response.getAggregateType()).isEqualTo("ORDER");
        assertThat(response.getAggregateId()).isEqualTo(42L);
        assertThat(response.getPayload()).isEqualTo("{\"orderId\":42}");
        assertThat(response.getLastError()).isEqualTo("connection reset");
        assertThat(response.getRetryCount()).isEqualTo(3);
        assertThat(response.getSource()).isEqualTo("OUTBOX");
        assertThat(response.getStatus()).isEqualTo("REQUEUED");
        assertThat(response.getCreatedAt()).isEqualTo(LocalDateTime.of(2026, 1, 1, 10, 0));
        assertThat(response.getRequeuedAt()).isEqualTo(LocalDateTime.of(2026, 1, 2, 11, 0));
    }

    @Test
    void from_truncatesOversizedPayload() {
        DeadLetterEvent event = new DeadLetterEvent();
        event.setPayload("x".repeat(2500));
        event.setStatus(null);

        DeadLetterEventResponse response = DeadLetterEventResponseMapper.from(event);

        assertThat(response.getPayload()).hasSize(2000 + "...(truncated)".length())
                .endsWith("...(truncated)");
        assertThat(response.getStatus()).isNull();
    }

    @Test
    void from_boundaryPayloadAt2000IsUntouched_andNullPayloadStaysNull() {
        DeadLetterEvent exact = new DeadLetterEvent();
        exact.setPayload("y".repeat(2000));
        assertThat(DeadLetterEventResponseMapper.from(exact).getPayload()).hasSize(2000);

        DeadLetterEvent empty = new DeadLetterEvent();
        assertThat(DeadLetterEventResponseMapper.from(empty).getPayload()).isNull();
    }
}

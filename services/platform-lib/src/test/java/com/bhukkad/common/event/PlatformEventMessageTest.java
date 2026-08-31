package com.bhukkad.common.event;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlatformEventMessageTest {

    @Test
    void of_generatesEventIdAndCorrelationId() {
        PlatformEventMessage msg = PlatformEventMessage.of("OrderCreated", "42", "{\"id\":42}");

        assertThat(msg.eventId()).isNotBlank();
        assertThat(msg.eventType()).isEqualTo("OrderCreated");
        assertThat(msg.aggregateId()).isEqualTo("42");
        assertThat(msg.schemaVersion()).isEqualTo(1);
        assertThat(msg.occurredAt()).isNotNull();
        assertThat(msg.correlationId()).isEqualTo(msg.eventId());
        assertThat(msg.traceparent()).isNull();
    }

    @Test
    void of_withCorrelationId_usesIt() {
        PlatformEventMessage msg = PlatformEventMessage.of("OrderCreated", "42", "corr-1", "{}");

        assertThat(msg.correlationId()).isEqualTo("corr-1");
        assertThat(msg.eventId()).isNotEqualTo("corr-1");
    }

    @Test
    void constructor_rejectsBlankEventType() {
        assertThatThrownBy(() -> new PlatformEventMessage(
                "e", " ", 1, Instant.now(), "a", "c", null, "{}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventType");
    }

    @Test
    void constructor_rejectsNullPayload() {
        assertThatThrownBy(() -> new PlatformEventMessage(
                "e", "OrderCreated", 1, Instant.now(), "a", "c", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payload");
    }

    @Test
    void roundTrip_serializesAndDeserializes() {
        PlatformEventMessage msg = new PlatformEventMessage(
                "evt-1", "OrderCreated", 2, Instant.parse("2026-08-30T08:00:00Z"),
                "77", "corr-9", "00-abcdefabcdefabcdefabcdefabcdefab-1234567890abcdef-01", "{\"x\":1}");

        String json = msg.toJson();
        PlatformEventMessage back = PlatformEventMessage.fromJson(json);

        assertThat(back).isEqualTo(msg);
    }

    @Test
    void fromJson_invalidJson_throws() {
        assertThatThrownBy(() -> PlatformEventMessage.fromJson("not-json{"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

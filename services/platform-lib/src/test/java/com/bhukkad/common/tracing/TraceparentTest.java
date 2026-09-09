package com.bhukkad.common.tracing;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TraceparentTest {

    private static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String SPAN = "00f067aa0ba902b7";

    @Test
    void create_producesW3cFormat() {
        Traceparent tp = Traceparent.create(TRACE, SPAN, true);
        assertThat(tp.toString()).isEqualTo("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
        assertThat(tp.isSampled()).isTrue();
    }

    @Test
    void create_notSampled_setsFlags00() {
        Traceparent tp = Traceparent.create(TRACE, SPAN, false);
        assertThat(tp.toString()).endsWith("-00");
        assertThat(tp.isSampled()).isFalse();
    }

    @Test
    void parse_validHeader_returnsTraceparent() {
        String header = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        Optional<Traceparent> tp = Traceparent.parse(header);
        assertThat(tp).isPresent();
        assertThat(tp.get().traceId()).isEqualTo(TRACE);
        assertThat(tp.get().spanId()).isEqualTo(SPAN);
        assertThat(tp.get().isSampled()).isTrue();
    }

    @Test
    void parse_blankAndNull_returnsEmpty() {
        assertThat(Traceparent.parse(null)).isEmpty();
        assertThat(Traceparent.parse("   ")).isEmpty();
    }

    @Test
    void parse_malformed_returnsEmpty() {
        assertThat(Traceparent.parse("00-abc")).isEmpty();
        assertThat(Traceparent.parse("00-zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz-00f067aa0ba902b7-01")).isEmpty();
        assertThat(Traceparent.parse("00-4bf92f3577b34da6a3ce929d0e0e4736-1234-01")).isEmpty();
        assertThat(Traceparent.parse("01-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"))
                .as("future versions are still structurally valid").isPresent();
    }

    @Test
    void childSpan_keepsTraceAndFlags_changesSpan() {
        Traceparent tp = Traceparent.create(TRACE, SPAN, true);
        Traceparent child = Traceparent.parse(tp.childSpan("aaaaaaaaaaaaaaaa")).orElseThrow();
        assertThat(child.traceId()).isEqualTo(TRACE);
        assertThat(child.spanId()).isEqualTo("aaaaaaaaaaaaaaaa");
        assertThat(child.isSampled()).isTrue();
    }

    @Test
    void constructor_rejectsInvalidFields() {
        assertThatThrownBy(() -> new Traceparent("0", TRACE, SPAN, "01")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Traceparent("00", "short", SPAN, "01")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Traceparent("00", TRACE, "x", "01")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Traceparent("00", TRACE, SPAN, "ffz")).isInstanceOf(IllegalArgumentException.class);
    }
}

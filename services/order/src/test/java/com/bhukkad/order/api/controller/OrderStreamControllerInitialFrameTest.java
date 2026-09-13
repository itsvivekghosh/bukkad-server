package com.bhukkad.order.api.controller;

import com.bhukkad.common.security.TokenPrincipal;
import com.bhukkad.order.api.OrderSseRegistry;
import com.bhukkad.order.domain.entity.Order;
import com.bhukkad.order.domain.repository.OrderRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * BUG-A: headerless SseEmitters never flush the SSE response headers at the
 * edge, so the gateway's 8s response-timeout turns every idle-but-valid
 * kitchen/customer/rider stream into a 504. Every opened stream must write an
 * initial comment frame right after registry insertion.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderStreamControllerInitialFrameTest {

    @Mock private OrderRepository orderRepository;

    private final OrderSseRegistry registry = new OrderSseRegistry(null);

    private static final TokenPrincipal OWNER =
            new TokenPrincipal(1L, "o@bhukkad.dev", "RESTAURANT_OWNER");
    private static final TokenPrincipal CUSTOMER =
            new TokenPrincipal(7L, "c@bhukkad.dev", "CUSTOMER");
    private static final TokenPrincipal AGENT =
            new TokenPrincipal(3L, "a@bhukkad.dev", "DELIVERY_AGENT");

    /** Emitter that records the SSE frames written through it. */
    static class RecordingEmitter extends SseEmitter {
        final List<String> frames = new ArrayList<>();

        RecordingEmitter() {
            super(30 * 60_000L);
        }

        @Override
        public void send(SseEventBuilder builder) {
            StringBuilder text = new StringBuilder();
            for (ResponseBodyEmitter.DataWithMediaType part : builder.build()) {
                text.append(part.getData());
            }
            frames.add(text.toString());
        }
    }

    private static class TestableController extends OrderStreamController {
        final List<RecordingEmitter> created = new ArrayList<>();

        TestableController(OrderRepository orders, OrderSseRegistry registry) {
            super(orders, registry);
        }

        @Override
        SseEmitter newEmitter() {
            RecordingEmitter emitter = new RecordingEmitter();
            created.add(emitter);
            return emitter;
        }
    }

    private TestableController controller() {
        return new TestableController(orderRepository, registry);
    }

    private static void assertInitialFrame(SseEmitter emitter, TestableController controller) {
        assertThat(controller.created).hasSize(1);
        assertThat(((RecordingEmitter) emitter).frames)
                .isEqualTo(List.of(":connected\n\n"));
    }

    @Test
    void kitchenStream_writesInitialCommentFrame() {
        TestableController controller = controller();
        SseEmitter emitter = controller.kitchen(OWNER, 9L);
        assertInitialFrame(emitter, controller);
    }

    @Test
    void customerStream_writesInitialCommentFrame() {
        Order order = new Order();
        order.setId(42L);
        order.setCustomerId(7L);
        when(orderRepository.findById(42L)).thenReturn(Optional.of(order));
        TestableController controller = controller();
        SseEmitter emitter = controller.customer(CUSTOMER, 42L);
        assertInitialFrame(emitter, controller);
    }

    @Test
    void riderStream_writesInitialCommentFrame() {
        TestableController controller = controller();
        SseEmitter emitter = controller.rider(AGENT);
        assertInitialFrame(emitter, controller);
    }

    @Test
    void trackingTokenStream_validToken_opensStreamWithInitialFrame() {
        Order order = new Order();
        order.setId(42L);
        order.setCustomerId(7L);
        when(orderRepository.findById(42L)).thenReturn(Optional.of(order));
        TestableController controller = controller();
        Map<String, Object> minted = controller.trackingToken(CUSTOMER, 42L);
        String token = String.valueOf(minted.get("token"));

        ResponseEntity<SseEmitter> stream = controller.customerToken(42L, token);

        assertThat(stream.getStatusCodeValue()).isEqualTo(200);
        assertThat(((RecordingEmitter) stream.getBody()).frames)
                .isEqualTo(List.of(":connected\n\n"));
    }

    @Test
    void trackingTokenStream_invalidToken_rejectedWithoutOpeningStream() {
        TestableController controller = controller();
        ResponseEntity<SseEmitter> rejected = controller.customerToken(42L, "no-such-token");
        assertThat(rejected.getStatusCodeValue()).isEqualTo(401);
        assertThat(rejected.getBody()).isNull();
        assertThat(controller.created).isEmpty();
    }
}

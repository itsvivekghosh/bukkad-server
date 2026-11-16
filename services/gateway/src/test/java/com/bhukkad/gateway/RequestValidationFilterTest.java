package com.bhukkad.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link RequestValidationFilter}: strict content-type and JSON
 * validation, explicit non-JSON route exclusions, and edge filter ordering.
 */
class RequestValidationFilterTest {

    private static final class Chain implements GatewayFilterChain {

        private boolean invoked;
        private byte[] body;

        @Override
        public Mono<Void> filter(ServerWebExchange exchange) {
            invoked = true;
            return DataBufferUtils.join(exchange.getRequest().getBody())
                    .map(buffer -> {
                        byte[] bytes = new byte[buffer.readableByteCount()];
                        buffer.read(bytes);
                        DataBufferUtils.release(buffer);
                        return bytes;
                    })
                    .doOnNext(bytes -> body = bytes)
                    .then();
        }
    }

    private static RequestValidationFilter filter(int maxDepth, int maxKeyCount) {
        GatewayValidationProperties properties = new GatewayValidationProperties();
        properties.setMaxDepth(maxDepth);
        properties.setMaxKeyCount(maxKeyCount);
        return new RequestValidationFilter(properties);
    }

    private static MockServerWebExchange jsonExchange(String path, String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return MockServerWebExchange.from(MockServerHttpRequest.post(path)
                .contentType(MediaType.APPLICATION_JSON)
                .contentLength(bytes.length)
                .body(body));
    }

    @Test
    void validJson_passesAndPreservesBody() {
        Chain chain = new Chain();
        String body = "{\"name\":\"test\",\"nested\":{\"ok\":true}}";
        MockServerWebExchange exchange = jsonExchange("/api/v1/orders", body);

        filter(10, 1000).filter(exchange, chain).block();

        assertThat(chain.invoked).isTrue();
        assertThat(chain.body).isEqualTo(body.getBytes(StandardCharsets.UTF_8));
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    void wrongContentType_returns415() {
        Chain chain = new Chain();
        byte[] bytes = "not-json".getBytes(StandardCharsets.UTF_8);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/orders")
                        .contentType(MediaType.TEXT_PLAIN)
                        .contentLength(bytes.length)
                        .body("not-json"));

        filter(10, 1000).filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode())
                .isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(exchange.getResponse().getBodyAsString().block())
                .contains("\"code\":\"UNSUPPORTED_MEDIA_TYPE\"");
        assertThat(chain.invoked).isFalse();
    }

    @Test
    void tooDeepJson_returns400() {
        Chain chain = new Chain();
        MockServerWebExchange exchange = jsonExchange(
                "/api/v1/orders", "{\"a\":{\"b\":{\"c\":true}}}");

        filter(2, 1000).filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exchange.getResponse().getBodyAsString().block())
                .contains("\"code\":\"JSON_DEPTH_EXCEEDED\"");
    }

    @Test
    void duplicateKeys_return400() {
        Chain chain = new Chain();
        MockServerWebExchange exchange = jsonExchange(
                "/api/v1/auth/login", "{\"email\":\"a@example.com\",\"email\":\"b@example.com\"}");

        filter(10, 1000).filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exchange.getResponse().getBodyAsString().block())
                .contains("\"code\":\"INVALID_JSON\"");
    }

    @Test
    void oversizedKeyCount_returns400() {
        Chain chain = new Chain();
        MockServerWebExchange exchange = jsonExchange(
                "/api/v1/orders", "{\"a\":1,\"b\":2,\"c\":3}");

        filter(10, 2).filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exchange.getResponse().getBodyAsString().block())
                .contains("\"code\":\"JSON_KEY_LIMIT_EXCEEDED\"");
    }

    @Test
    void malformedJson_returns400() {
        Chain chain = new Chain();
        MockServerWebExchange exchange = jsonExchange("/api/v1/orders", "{\"name\":");

        filter(10, 1000).filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exchange.getResponse().getBodyAsString().block())
                .contains("\"code\":\"INVALID_JSON\"");
    }

    @Test
    void multipartUploadRoute_isExcluded() {
        Chain chain = new Chain();
        String body = "multipart-payload";
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post(
                                "/api/v1/support/chat/rooms/1/messages/2/attachments")
                        .contentType(MediaType.MULTIPART_FORM_DATA)
                        .contentLength(body.getBytes(StandardCharsets.UTF_8).length)
                        .body(body));

        filter(10, 1000).filter(exchange, chain).block();

        assertThat(chain.invoked).isTrue();
        assertThat(chain.body).isEqualTo(body.getBytes(StandardCharsets.UTF_8));
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    void rawWebhookRoute_isExcluded() {
        Chain chain = new Chain();
        String body = "provider-raw-payload";
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/payments/webhooks/razorpay")
                        .contentType(MediaType.TEXT_PLAIN)
                        .contentLength(body.getBytes(StandardCharsets.UTF_8).length)
                        .body(body));

        filter(10, 1000).filter(exchange, chain).block();

        assertThat(chain.invoked).isTrue();
        assertThat(chain.body).isEqualTo(body.getBytes(StandardCharsets.UTF_8));
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    void emptyBody_passesWithoutContentType() {
        Chain chain = new Chain();
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/orders").build());

        filter(10, 1000).filter(exchange, chain).block();

        assertThat(chain.invoked).isTrue();
        assertThat(chain.body).isNull();
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    void sseGetRequest_passesWithoutTouchingStream() {
        Chain chain = new Chain();
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/live/kitchen/1")
                        .accept(MediaType.TEXT_EVENT_STREAM));

        filter(10, 1000).filter(exchange, chain).block();

        assertThat(chain.invoked).isTrue();
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    void validationRunsAfterSizeLimit() {
        assertThat(RequestValidationFilter.ORDER).isGreaterThan(EdgeSizeLimitFilter.ORDER);
    }
}

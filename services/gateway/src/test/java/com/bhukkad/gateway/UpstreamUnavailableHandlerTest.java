package com.bhukkad.gateway;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.resolver.dns.DnsFailureStub;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.lang.reflect.Field;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 503 UPSTREAM_UNAVAILABLE envelope mapping (route failures must not surface
 * as opaque 500s), passthrough of unrelated errors, committed-response guard,
 * and the JSON-failure fallback body.
 */
class UpstreamUnavailableHandlerTest {

    private final UpstreamUnavailableHandler handler = new UpstreamUnavailableHandler();

    private static MockServerWebExchange exchange() {
        return MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/orders/9"));
    }

    private static WebClientRequestException webClientRequestFailure() {
        return new WebClientRequestException(
                new ConnectException("refused"),
                org.springframework.http.HttpMethod.GET,
                URI.create("http://identity:8080"),
                new org.springframework.http.HttpHeaders());
    }

    private static Stream<Arguments> upstreamFailures() {
        return Stream.of(
                Arguments.of(new ConnectException("Connection refused")),
                Arguments.of(new UnknownHostException("identity.svc.cluster.local")),
                Arguments.of(new TimeoutException("did not observe")),
                Arguments.of(webClientRequestFailure()));
    }

    @ParameterizedTest
    @MethodSource("upstreamFailures")
    void upstreamFailures_answer503Envelope(Throwable failure) {
        MockServerWebExchange exchange = exchange();

        StepVerifier.create(handler.handle(exchange, failure)).expectComplete().verify();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(exchange.getResponse().getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(exchange.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("5");
        String body = exchange.getResponse().getBodyAsString().block();
        assertThat(body)
                .contains("\"status\":503")
                .contains("\"code\":\"UPSTREAM_UNAVAILABLE\"")
                .contains("\"path\":\"/api/v1/orders/9\"")
                .contains("temporarily unavailable");
    }

    @Test
    void nettyNameResolver_failuresAreMatchedByClassName() {
        MockServerWebExchange exchange = exchange();
        StepVerifier.create(handler.handle(exchange, new DnsFailureStub("failed to resolve")))
                .expectComplete().verify();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void nestedCause_isDetectedThroughTheChain() {
        MockServerWebExchange exchange = exchange();
        RuntimeException wrapped = new RuntimeException("route failed",
                new IllegalStateException("io", new ConnectException("refused")));
        StepVerifier.create(handler.handle(exchange, wrapped)).expectComplete().verify();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void unrelatedErrors_arePropagatedToDefaultHandling() {
        MockServerWebExchange exchange = exchange();
        IllegalStateException boom = new IllegalStateException("real server fault");
        StepVerifier.create(handler.handle(exchange, boom))
                .expectErrorMessage("real server fault").verify();
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    void selfCausedThrowable_terminatesWithoutStackOverflow() {
        class SelfCause extends RuntimeException {
            SelfCause() {
                super("loop");
            }

            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        }
        ServerWebExchange exchange = exchange();
        SelfCause ex = new SelfCause();
        StepVerifier.create(handler.handle(exchange, ex))
                .expectError(SelfCause.class).verify();
    }

    @Test
    void committedResponse_isNotRewritten() {
        MockServerWebExchange exchange = exchange();
        exchange.getResponse().setComplete().block();
        assertThat(exchange.getResponse().isCommitted()).isTrue();

        StepVerifier.create(handler.handle(exchange, new ConnectException("refused")))
                .expectError(ConnectException.class).verify();
    }

    @Test
    void jsonSerializationFailure_fallsBackToMinimalBody() throws Exception {
        ObjectMapper broken = new ObjectMapper() {
            @Override
            public byte[] writeValueAsBytes(Object value) throws com.fasterxml.jackson.core.JsonProcessingException {
                throw new com.fasterxml.jackson.core.JsonGenerationException(
                        "simulated mapper failure", (JsonGenerator) null);
            }
        };
        UpstreamUnavailableHandler unit = new UpstreamUnavailableHandler();
        Field mapperField = UpstreamUnavailableHandler.class.getDeclaredField("mapper");
        mapperField.setAccessible(true);
        mapperField.set(unit, broken);

        MockServerWebExchange exchange = exchange();
        StepVerifier.create(unit.handle(exchange, new ConnectException("refused")))
                .expectComplete().verify();

        String body = exchange.getResponse().getBodyAsString().block();
        assertThat(body).isEqualTo("{\"status\":503,\"code\":\"UPSTREAM_UNAVAILABLE\"}");
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void handleReturnsVoidMono() {
        Mono<Void> result = handler.handle(exchange(), new ConnectException("refused"));
        assertThat(result).isNotNull();
        StepVerifier.create(result).expectComplete().verify();
    }
}

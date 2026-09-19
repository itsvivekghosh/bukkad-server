package com.bhukkad.gateway;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EdgeRequestHedgingFilterTest {

    private static final class Chain implements GatewayFilterChain {
        final java.util.concurrent.atomic.AtomicBoolean passed = new java.util.concurrent.atomic.AtomicBoolean();
        Function<ServerWebExchange, Mono<Void>> delegate = exchange -> Mono.empty();

        void setDelegate(Function<ServerWebExchange, Mono<Void>> delegate) {
            this.delegate = delegate;
        }

        @Override
        public Mono<Void> filter(ServerWebExchange exchange) {
            passed.set(true);
            return delegate.apply(exchange);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static EdgeRequestHedgingFilter filterWithSecondary(String secondaryUri, double probability) {
        ObjectProvider meterProvider = mock(ObjectProvider.class);
        when(meterProvider.getIfAvailable()).thenReturn(new SimpleMeterRegistry());
        WebClient.Builder builder = mock(WebClient.Builder.class);
        when(builder.build()).thenReturn(mock(WebClient.class));
        return new EdgeRequestHedgingFilter(builder, meterProvider, secondaryUri, probability);
    }

    @SuppressWarnings("unchecked")
    private static void mockHedgedWebClient(WebClient webClient,
                                            org.springframework.http.HttpStatusCode status,
                                            byte[] body) {
        WebClient.RequestBodyUriSpec uriSpec = mock(WebClient.RequestBodyUriSpec.class);
        WebClient.RequestBodySpec headersSpec = mock(WebClient.RequestBodySpec.class);
        org.springframework.web.reactive.function.client.WebClient.ResponseSpec responseSpec =
                mock(org.springframework.web.reactive.function.client.WebClient.ResponseSpec.class);

        when(webClient.method(any())).thenReturn(uriSpec);
        when(uriSpec.uri(any(java.net.URI.class))).thenReturn(headersSpec);
        when(headersSpec.headers(any())).thenReturn(headersSpec);
        when(headersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(byte[].class)).thenReturn(Mono.just(body));
    }

    @SuppressWarnings("unchecked")
    private static void setWriteHandler(
            org.springframework.mock.http.server.reactive.MockServerHttpResponse response,
            java.util.function.Function<? super reactor.core.publisher.Flux<org.springframework.core.io.buffer.DataBuffer>, ? extends reactor.core.publisher.Mono<Void>> handler) {
        try {
            Field f = org.springframework.mock.http.server.reactive.MockServerHttpResponse.class
                    .getDeclaredField("writeHandler");
            f.setAccessible(true);
            f.set(response, handler);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void noSecondaryUri_skipsHedging() {
        EdgeRequestHedgingFilter filter = filterWithSecondary("", 0.02);
        Chain chain = new Chain();

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/restaurants/public?city=Bangalore"));
        filter.filter(exchange, chain).block();

        assertThat(chain.passed).isTrue();
    }

    @Test
    void nonGetMethod_skipsHedging() {
        EdgeRequestHedgingFilter filter = filterWithSecondary("http://secondary:8091", 0.02);
        Chain chain = new Chain();

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/restaurants/public"));
        filter.filter(exchange, chain).block();

        assertThat(chain.passed).isTrue();
    }

    @Test
    void nonCacheablePath_skipsHedging() {
        EdgeRequestHedgingFilter filter = filterWithSecondary("http://secondary:8091", 0.02);
        Chain chain = new Chain();

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/orders/123"));
        filter.filter(exchange, chain).block();

        assertThat(chain.passed).isTrue();
    }

    @Test
    void probabilityZero_skipsHedging() {
        EdgeRequestHedgingFilter filter = filterWithSecondary("http://secondary:8091", 0.0);
        Chain chain = new Chain();

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/restaurants/public?city=Bangalore"));
        filter.filter(exchange, chain).block();

        assertThat(chain.passed).isTrue();
    }

    @Test
    void hedgedResponseWins_commitsHedgedBody() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ObjectProvider meterProvider = mock(ObjectProvider.class);
        when(meterProvider.getIfAvailable()).thenReturn(meters);

        WebClient webClient = mock(WebClient.class);
        mockHedgedWebClient(webClient, HttpStatus.OK, "{\"hedged\":true}".getBytes());

        WebClient.Builder builder = mock(WebClient.Builder.class);
        when(builder.build()).thenReturn(webClient);

        EdgeRequestHedgingFilter filter = new EdgeRequestHedgingFilter(
                builder, meterProvider, "http://secondary:8091", 1.0);

        Chain chain = new Chain();
        // Use Mono.never() so the hedged request always wins the race.
        chain.setDelegate(exchange -> Mono.never());

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/restaurants/public?city=Bangalore"));

        java.util.concurrent.atomic.AtomicReference<byte[]> captured = new java.util.concurrent.atomic.AtomicReference<>();
        setWriteHandler(
                (org.springframework.mock.http.server.reactive.MockServerHttpResponse) exchange.getResponse(),
                flux -> flux
                        .doOnNext(buf -> {
                            byte[] bytes = new byte[buf.readableByteCount()];
                            buf.read(bytes);
                            captured.set(bytes);
                        })
                        .then(Mono.empty()));

        filter.filter(exchange, chain).block();

        assertThat(captured.get()).isNotNull();
        assertThat(new String(captured.get())).contains("hedged");
        assertThat(chain.passed).isTrue();
        assertThat(meters.counter("edge_hedge_won_total").count()).isEqualTo(1);
    }

    @Test
    void originalWins_whenHedgedErrors() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ObjectProvider meterProvider = mock(ObjectProvider.class);
        when(meterProvider.getIfAvailable()).thenReturn(meters);

        WebClient webClient = mock(WebClient.class);
        WebClient.RequestBodyUriSpec uriSpec = mock(WebClient.RequestBodyUriSpec.class);
        WebClient.RequestBodySpec headersSpec = mock(WebClient.RequestBodySpec.class);
        org.springframework.web.reactive.function.client.WebClient.ResponseSpec responseSpec =
                mock(org.springframework.web.reactive.function.client.WebClient.ResponseSpec.class);

        when(webClient.method(any())).thenReturn(uriSpec);
        when(uriSpec.uri(any(java.net.URI.class))).thenReturn(headersSpec);
        when(headersSpec.headers(any())).thenReturn(headersSpec);
        when(headersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(byte[].class))
                .thenReturn(Mono.<byte[]>empty()
                        .delaySubscription(java.time.Duration.ofMillis(10))
                        .then(Mono.error(new RuntimeException("hedged failed"))));

        WebClient.Builder builder = mock(WebClient.Builder.class);
        when(builder.build()).thenReturn(webClient);

        EdgeRequestHedgingFilter filter = new EdgeRequestHedgingFilter(
                builder, meterProvider, "http://secondary:8091", 1.0);

        Chain chain = new Chain();
        java.util.concurrent.atomic.AtomicReference<byte[]> capturedBody =
                new java.util.concurrent.atomic.AtomicReference<>();
        chain.setDelegate(exchange -> {
            MockServerHttpResponse response = (MockServerHttpResponse) exchange.getResponse();
            response.setStatusCode(HttpStatus.OK);
            setWriteHandler(response, flux -> flux
                    .doOnNext(buf -> {
                        byte[] bytes = new byte[buf.readableByteCount()];
                        buf.read(bytes);
                        capturedBody.set(bytes);
                    })
                    .then(Mono.empty()));
            return response.writeWith(Mono.just(
                    response.bufferFactory().wrap("original-wins".getBytes())));
        });

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/restaurants/public?city=Bangalore"));
        filter.filter(exchange, chain).block();

        assertThat(capturedBody.get()).isNotNull();
        assertThat(new String(capturedBody.get())).contains("original-wins");
        assertThat(chain.passed).isTrue();
    }

    @Test
    void hedgedNon2xx_originalWins() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ObjectProvider meterProvider = mock(ObjectProvider.class);
        when(meterProvider.getIfAvailable()).thenReturn(meters);

        WebClient webClient = mock(WebClient.class);
        WebClient.RequestBodyUriSpec uriSpec = mock(WebClient.RequestBodyUriSpec.class);
        WebClient.RequestBodySpec headersSpec = mock(WebClient.RequestBodySpec.class);
        org.springframework.web.reactive.function.client.WebClient.ResponseSpec responseSpec =
                mock(org.springframework.web.reactive.function.client.WebClient.ResponseSpec.class);

        when(webClient.method(any())).thenReturn(uriSpec);
        when(uriSpec.uri(any(java.net.URI.class))).thenReturn(headersSpec);
        when(headersSpec.headers(any())).thenReturn(headersSpec);
        when(headersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(byte[].class))
                .thenReturn(Mono.<byte[]>empty()
                        .delaySubscription(java.time.Duration.ofMillis(10))
                        .then(Mono.error(new org.springframework.web.reactive.function.client.WebClientResponseException(
                                "500", 500, "Server Error", null, null, null))));

        WebClient.Builder builder = mock(WebClient.Builder.class);
        when(builder.build()).thenReturn(webClient);

        EdgeRequestHedgingFilter filter = new EdgeRequestHedgingFilter(
                builder, meterProvider, "http://secondary:8091", 1.0);

        Chain chain = new Chain();
        java.util.concurrent.atomic.AtomicReference<byte[]> capturedBody =
                new java.util.concurrent.atomic.AtomicReference<>();
        chain.setDelegate(exchange -> {
            MockServerHttpResponse response = (MockServerHttpResponse) exchange.getResponse();
            response.setStatusCode(HttpStatus.OK);
            setWriteHandler(response, flux -> flux
                    .doOnNext(buf -> {
                        byte[] bytes = new byte[buf.readableByteCount()];
                        buf.read(bytes);
                        capturedBody.set(bytes);
                    })
                    .then(Mono.empty()));
            return response.writeWith(Mono.just(
                    response.bufferFactory().wrap("original-wins".getBytes())));
        });

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/restaurants/public?city=Bangalore"));
        filter.filter(exchange, chain).block();

        assertThat(capturedBody.get()).isNotNull();
        assertThat(new String(capturedBody.get())).contains("original-wins");
        assertThat(chain.passed).isTrue();
    }
}

package com.bhukkad.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit coverage for the request-direction edge hygiene (audit batch A):
 * Proxy-Connection removal, TE-allowed-only-as-trailers and CORS response
 * header dedupe. Response-commit behaviour is exercised through the filter
 * chain below using MockServerWebExchange.
 */
class TransportHeaderHygieneFilterTest {

    private final TransportHeaderHygieneFilter filter = new TransportHeaderHygieneFilter();

    private ServerWebExchange forwarded(MockServerWebExchange exchange) {
        // Capture what the downstream chain sees via the mutated GATEWAY_REQUEST_URL_ATTR-less
        // pattern: the filter mutates the request before chain.filter.
        final ServerWebExchange[] seen = new ServerWebExchange[1];
        filter.filter(exchange, ex -> {
            seen[0] = ex;
            return Mono.empty();
        }).block();
        return seen[0];
    }

    @Test
    void removesProxyConnection() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/orders").header("Proxy-Connection", "keep-alive"));

        ServerWebExchange forwarded = forwarded(exchange);

        assertThat(forwarded.getRequest().getHeaders().containsKey("Proxy-Connection")).isFalse();
    }

    @Test
    void keepsTrailersTe_dropsEverythingElse() {
        ServerWebExchange trailers = forwarded(MockServerWebExchange.from(
                MockServerHttpRequest.get("/x").header("TE", "trailers")));
        assertThat(trailers.getRequest().getHeaders().getFirst("TE")).isEqualTo("trailers");

        ServerWebExchange gzip = forwarded(MockServerWebExchange.from(
                MockServerHttpRequest.get("/x").header("TE", "gzip, deflate")));
        assertThat(gzip.getRequest().getHeaders().containsKey("TE")).isFalse();

        ServerWebExchange mixed = forwarded(MockServerWebExchange.from(
                MockServerHttpRequest.get("/x").header("TE", "trailers, deflate")));
        assertThat(mixed.getRequest().getHeaders().containsKey("TE")).isFalse();

        assertThat(TransportHeaderHygieneFilter.isTrailersOnly(List.of("Trailers"))).isTrue();
        assertThat(TransportHeaderHygieneFilter.isTrailersOnly(List.of())).isTrue();
    }

    @Test
    void passThroughWhenNothingToRemove() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/x").header("Accept", "*/*"));

        ServerWebExchange forwarded = forwarded(exchange);

        assertThat(forwarded).isSameAs(exchange);
    }

    @Test
    void deduplicatesCORSAllowHeadersOnResponse() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/x"));
        // Register the beforeCommit callback via the filter, then commit.
        filter.filter(exchange, ex -> Mono.empty()).block();
        exchange.getResponse().getHeaders().add("Access-Control-Allow-Origin", "https://a.bhukkad.com");
        exchange.getResponse().getHeaders().add("Access-Control-Allow-Origin", "https://evil.example");
        exchange.getResponse().getHeaders().add("Access-Control-Allow-Credentials", "true");
        exchange.getResponse().setComplete().block();

        List<String> origins = exchange.getResponse().getHeaders().get("Access-Control-Allow-Origin");
        assertThat(origins).containsExactly("https://a.bhukkad.com");
        assertThat(exchange.getResponse().getHeaders().get("Access-Control-Allow-Credentials"))
                .containsExactly("true");
    }

    @Test
    void secureCookieFilterOrderRunsAfterTransportHygiene() {
        assertThat(new SecureCookieFilter().getOrder())
                .isGreaterThan(TransportHeaderHygieneFilter.ORDER);
        assertThat(TransportHeaderHygieneFilter.ORDER)
                .isGreaterThan(UntrustedHeaderSanitizer.ORDER);
    }
}

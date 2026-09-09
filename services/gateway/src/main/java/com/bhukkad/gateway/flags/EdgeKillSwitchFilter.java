package com.bhukkad.gateway.flags;

import com.bhukkad.common.security.PlatformJwtValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * Per-route edge kill switch (migration plan W0 / gap A10). Routes opt in via
 * {@code metadata "edge-flag" -> <featureFlagKey>} in {@code GatewayConfig}.
 * When the flag evaluates disabled the request is answered 503 with the
 * platform ApiError shape and never reaches the upstream service; percentage
 * rollouts bucket on the caller's id taken from the bearer JWT when the
 * gateway shares the identity signing secret (anonymous callers follow the
 * boolean/global state).
 */
@Component
public class EdgeKillSwitchFilter implements org.springframework.cloud.gateway.filter.GlobalFilter, Ordered {

    public static final String ROUTE_FLAG_METADATA = "edge-flag";
    static final int ORDER = 10;

    private static final Logger log = LoggerFactory.getLogger(EdgeKillSwitchFilter.class);

    private final EdgeFeatureFlags flags;
    private final ObjectProvider<PlatformJwtValidator> jwtValidatorProvider;

    public EdgeKillSwitchFilter(EdgeFeatureFlags flags,
                                ObjectProvider<PlatformJwtValidator> jwtValidatorProvider) {
        this.flags = flags;
        this.jwtValidatorProvider = jwtValidatorProvider;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, org.springframework.cloud.gateway.filter.GatewayFilterChain chain) {
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        Object flagMeta = route == null ? null : route.getMetadata().get(ROUTE_FLAG_METADATA);
        if (!(flagMeta instanceof String flagKey)) {
            return chain.filter(exchange);
        }
        Long subject = subjectId(exchange);
        return flags.isRouteEnabled(flagKey, subject)
                .flatMap(enabled -> enabled
                        ? chain.filter(exchange)
                        : disabled(exchange, flagKey, route.getId()));
    }

    private Mono<Void> disabled(ServerWebExchange exchange, String flagKey, String routeId) {
        log.info("EDGE_KILL_SWITCH_BLOCKED | route={} | flag={} | path={}",
                routeId, flagKey, exchange.getRequest().getPath());
        exchange.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body = ("{\"code\":\"SERVICE_DISABLED\",\"message\":\"route disabled by feature flag "
                + flagKey + "\",\"traceId\":null}").getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(body);
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    /** Best-effort caller id for percentage rollouts (never a security decision). */
    private Long subjectId(ServerWebExchange exchange) {
        String header = exchange.getRequest().getHeaders().getFirst("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return null;
        }
        PlatformJwtValidator validator = jwtValidatorProvider.getIfAvailable();
        if (validator == null) {
            return null;
        }
        try {
            return validator.validate(header.substring(7))
                    .map(principal -> principal.userId())
                    .orElse(null);
        } catch (Exception ex) {
            return null;
        }
    }
}

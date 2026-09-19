package com.bhukkad.gateway;

import com.bhukkad.common.ratelimit.RedisRateLimitService;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;

/**
 * Gateway-side account lockout for POST /api/v1/auth/login (defense-in-depth
 * alongside the identity-service LoginLockoutService).
 *
 * <p>Uses a per-identifier Redis counter with a 15-minute lockout window. After
 * {@code app.auth.lockout.gateway-threshold} failures (default 5) within a
 * 15-minute sliding window the identifier is blocked for 15 minutes. The
 * check runs at order -25 (after body capture, before routing), so locked
 * accounts are rejected at the edge without consuming upstream capacity.</p>
 *
 * <p>Redis errors fail OPEN (log + metric) — availability over perfect
 * lockout, consistent with the platform's fail-open posture.</p>
 *
 * <p>The filter reads the identifier from two sources in order of priority:
 * <ol>
 *   <li>The pre-cached request body (stored by the gateway's body-capture
 *       filter under {@link #REQUEST_BODY_ATTR})</li>
 *   <li>The client's remote IP address as a last-resort identifier</li>
 * </ol>
 * Email extracted from the body takes precedence so lockout is per-account
 * rather than per-IP.</p>
 */
@Component
public class EdgeAccountLockoutFilter implements GlobalFilter, Ordered {

    /**
     * Exchange attribute key used by the body-capture filter to store the
     * fully-buffered request body as a UTF-8 string.  When the attribute is
     * present the filter reads the identifier directly from it, avoiding
     * a second body-subscription.
     */
    public static final String REQUEST_BODY_ATTR =
            "cachedRequestBody";

    static final int ORDER = -25;
    private static final Duration REDIS_TIMEOUT = Duration.ofSeconds(1);

    private static final Logger log = LoggerFactory.getLogger(EdgeAccountLockoutFilter.class);

    private static final DefaultRedisScript<Long> CHECK_SCRIPT = new DefaultRedisScript<>(
            """
            local key = KEYS[1]
            local ttl = redis.call('PTTL', key)
            if ttl and ttl > 0 then
              return 0 - ttl
            end
            local count = redis.call('INCR', KEYS[2])
            if count == 1 then
              redis.call('PEXPIRE', KEYS[2], ARGV[2])
            end
            if count >= tonumber(ARGV[1]) then
              redis.call('PSETEX', key, ARGV[3], '1')
              redis.call('DEL', KEYS[2])
              return 0 - tonumber(ARGV[3])
            end
            return count
            """,
            Long.class);

    private static final String KEY_PREFIX = RedisRateLimitService.PREFIX + "edge-lockout:";
    private static final String METRIC_LOCKOUT = "auth_edge_lockout_active";
    private static final String METRIC_BYPASS = "auth_edge_lockout_bypass";

    private final ObjectProvider<ReactiveStringRedisTemplate> redisProvider;
    private final ObjectProvider<MeterRegistry> meterRegistryProvider;
    private final int threshold;
    private final int windowMillis;
    private final int lockMillis;
    private final boolean enabled;

    public EdgeAccountLockoutFilter(
            ObjectProvider<ReactiveStringRedisTemplate> redisProvider,
            ObjectProvider<MeterRegistry> meterRegistryProvider,
            @Value("${app.auth.lockout.gateway-threshold:5}") int threshold,
            @Value("${app.auth.lockout.gateway-window-seconds:900}") int windowSeconds,
            @Value("${app.auth.lockout.gateway-lock-seconds:900}") int lockSeconds,
            @Value("${app.auth.lockout.gateway-enabled:true}") boolean enabled) {
        this.redisProvider = redisProvider;
        this.meterRegistryProvider = meterRegistryProvider;
        this.threshold = threshold;
        this.windowMillis = windowSeconds * 1000;
        this.lockMillis = lockSeconds * 1000;
        this.enabled = enabled;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!enabled) {
            return chain.filter(exchange);
        }
        var request = exchange.getRequest();
        String method = request.getMethod() != null ? request.getMethod().name() : null;
        String path = request.getPath().value();

        if (!"POST".equalsIgnoreCase(method)
                || !"/api/v1/auth/login".equals(path)) {

            return chain.filter(exchange);
        }

        ReactiveStringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            return chain.filter(exchange);
        }

        // Read identifier: prefer cached body, fall back to remote IP.
        Mono<String> identifierSource = getCachedBody(exchange)
                .flatMap(this::extractEmail)
                .switchIfEmpty(Mono.defer(() -> {
                        InetSocketAddress addr = request.getRemoteAddress();
                        return Mono.just(addr != null ? addr.getHostString() : "unknown");
                }));

        Mono<Void> result = identifierSource
                .flatMap(identifier -> checkLockout(redis, identifier, exchange, chain))
                .switchIfEmpty(chain.filter(exchange));
        return result;
    }

    private Mono<String> getCachedBody(ServerWebExchange exchange) {
        Object cached = exchange.getAttribute(REQUEST_BODY_ATTR);
        if (cached instanceof String body) {
            return Mono.just(body);
        }
        return readBody(exchange);
    }

    private Mono<String> readBody(ServerWebExchange exchange) {
        return exchange.getRequest().getBody()
                .reduce(new StringBuilder(), (sb, dataBuffer) -> {
                    try {
                        sb.append(dataBuffer.toString(
                                java.nio.charset.StandardCharsets.UTF_8));
                    } finally {
                        DataBufferUtils.release(dataBuffer);
                    }
                    return sb;
                })
                .map(StringBuilder::toString);
    }

    private Mono<String> extractEmail(String body) {
        if (body == null || body.isBlank()) {
            return Mono.empty();
        }
        int idx = body.indexOf("\"email\"");
        if (idx < 0) {
            return Mono.empty();
        }
        int colon = body.indexOf(':', idx);
        if (colon < 0) return Mono.empty();
        int start = body.indexOf('"', colon + 1);
        int end = body.indexOf('"', start + 1);
        if (start < 0 || end < 0) return Mono.empty();
        String email = body.substring(start + 1, end);
        return Mono.just(email);
    }

    private Mono<Void> checkLockout(ReactiveStringRedisTemplate redis,
                                    String identifier,
                                    ServerWebExchange exchange,
                                    GatewayFilterChain chain) {
        String normalized = identifier.toLowerCase(java.util.Locale.ROOT);
        String lockKey = KEY_PREFIX + "lock:" + normalized;
        String countKey = KEY_PREFIX + "count:" + normalized;
        return redis.execute(CHECK_SCRIPT,
                        List.of(lockKey, countKey),
                        List.of(String.valueOf(threshold),
                                String.valueOf(windowMillis),
                                String.valueOf(lockMillis)))
                .next()
                .timeout(REDIS_TIMEOUT)
                .defaultIfEmpty(1L)
                .onErrorResume(error -> {
                    log.debug("EDGE_LOCKOUT_REDIS_ERROR id={}: {}", normalized, error.getMessage());
                    count(METRIC_BYPASS);
                    return Mono.just(1L);
                })
                .flatMap(result -> {
                    boolean deny = result < 0;
                    return deny
                            ? deny(exchange, lockMillis)
                            : chain.filter(exchange);
                });
    }

    private Mono<Void> deny(ServerWebExchange exchange, int lockMillis) {
        count(METRIC_LOCKOUT);
        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        int retryAfterSeconds = Math.max(1, (lockMillis + 999) / 1000);
        exchange.getResponse().getHeaders().add("Retry-After", String.valueOf(retryAfterSeconds));
        return exchange.getResponse().setComplete();
    }

    private void count(String metric) {
        MeterRegistry meters = meterRegistryProvider.getIfAvailable();
        if (meters != null) {
            meters.counter(metric).increment();
        }
    }
}

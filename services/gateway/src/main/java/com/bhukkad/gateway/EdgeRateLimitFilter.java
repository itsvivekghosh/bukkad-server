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
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;

/**
 * Edge Redis bucket rate limiter (audit V-18 / PERF-1.3) on the abuse-prone
 * public surfaces declared in the route table: auth login, order create, and
 * payment webhook callbacks.
 *
 * <p>Runs as a {@link GlobalFilter} BEFORE routing (order -30, after the
 * header sanitizers, before the kill switch at 10) so excess load never
 * reaches service thread pools or DB pools. Buckets on
 * {@code authSubject|clientIP} — the validated JWT subject when this edge
 * shares the identity signing secret, else the socket IP (X-Forwarded-For is
 * spoofable and intentionally NOT honoured, same policy as
 * {@code common.web.WebRateLimitKeyResolver}).</p>
 *
 * <p>The window logic reuses the platform-lib Lua verbatim
 * ({@link RedisRateLimitService#RATE_LIMIT_LUA} — INCR + PEXPIRE + limit
 * compare in ONE atomic script), executed on the reactive Redis template so
 * nothing blocks the Netty event loop. Denials answer 429 with
 * {@code Retry-After} and the platform envelope; every decision is counted
 * ({@code ratelimit_allowed} / {@code ratelimit_denied}). Redis errors and
 * timeouts are fail-OPEN by default (edge availability) and counted via
 * {@code ratelimit_bypass_redis_error} — toggling
 * {@code app.rate-limit.fail-open-on-redis-error} flips the posture.</p>
 */
@Component
public class EdgeRateLimitFilter implements GlobalFilter, Ordered {

    static final int ORDER = -30; // after header hygiene (-40), before kill switch (10)
    /** Bound on the Redis round trip; a timeout fails open, never stalls the edge. */
    static final Duration REDIS_COMMAND_TIMEOUT = Duration.ofSeconds(1);

    private static final Logger log = LoggerFactory.getLogger(EdgeRateLimitFilter.class);

    /** Same script + key prefix as the blocking platform-lib service. */
    private static final DefaultRedisScript<Long> SCRIPT =
            new DefaultRedisScript<>(RedisRateLimitService.RATE_LIMIT_LUA, Long.class);
    /** Edge bucket keys sit under the service prefix with the "edge-" bucket name. */
    private static final String KEY_PREFIX = RedisRateLimitService.PREFIX;

    private record Rule(String bucket, HttpMethod method, PathPattern path, int limit, int windowSeconds) {
    }

    private static final List<Rule> RULES = List.of(
            // Login brute-force/credential-stuffing wall (§6 PERF-1.3: /auth/login).
            rule("edge-login", HttpMethod.POST, "/api/v1/auth/login", 10, 60),
            // Order create (money path; also the legacy customer-scoped shape).
            rule("edge-order-create", HttpMethod.POST, "/api/v1/orders", 20, 60),
            rule("edge-order-create", HttpMethod.POST, "/api/v1/customers/*/orders", 20, 60),
            // Razorpay webhook callbacks — generous enough for gateway bursts,
            // closed enough to cap replay floods (V-11 mount point).
            rule("edge-webhook", HttpMethod.POST, "/api/v1/payments/webhooks/**", 600, 60));

    private final ObjectProvider<ReactiveStringRedisTemplate> redisProvider;
    private final ObjectProvider<MeterRegistry> meterRegistryProvider;
    private final ObjectProvider<com.bhukkad.common.security.PlatformJwtValidator> jwtValidatorProvider;
    private final boolean enabled;
    private final boolean failOpenOnRedisError;

    public EdgeRateLimitFilter(ObjectProvider<ReactiveStringRedisTemplate> redisProvider,
                               ObjectProvider<MeterRegistry> meterRegistryProvider,
                               ObjectProvider<com.bhukkad.common.security.PlatformJwtValidator> jwtValidatorProvider,
                               @Value("${app.edge.rate-limit.enabled:true}") boolean enabled,
                               @Value("${app.rate-limit.fail-open-on-redis-error:true}") boolean failOpenOnRedisError) {
        this.redisProvider = redisProvider;
        this.meterRegistryProvider = meterRegistryProvider;
        this.jwtValidatorProvider = jwtValidatorProvider;
        this.enabled = enabled;
        this.failOpenOnRedisError = failOpenOnRedisError;
    }

    private static Rule rule(String bucket, HttpMethod method, String pattern, int limit, int windowSeconds) {
        return new Rule(bucket, method, new PathPatternParser().parse(pattern), limit, windowSeconds);
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
        Rule rule = matchRule(exchange);
        if (rule == null) {
            return chain.filter(exchange);
        }
        ReactiveStringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            // No Redis configured at all (local dev): limiter inert, counted once.
            count(RedisRateLimitService.METRIC_BYPASS_REDIS_ERROR, rule.bucket());
            return chain.filter(exchange);
        }
        // Subject extraction may touch JWT parsing — keep it off the event loop.
        return Mono.fromCallable(() -> bucketIdentifier(exchange))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(identifier -> {
                    String key = KEY_PREFIX + rule.bucket() + ":" + identifier;
                    return reactor.core.publisher.Flux.defer(() -> redis.execute(SCRIPT, List.of(key),
                                    List.of(String.valueOf(rule.windowSeconds() * 1000L),
                                            String.valueOf(rule.limit()))))
                            .next()
                            .timeout(REDIS_COMMAND_TIMEOUT)
                            .defaultIfEmpty((long) rule.limit()) // empty reply → allow
                            .onErrorResume(error -> {
                                count(RedisRateLimitService.METRIC_BYPASS_REDIS_ERROR, rule.bucket());
                                if (log.isDebugEnabled()) {
                                    log.debug("EDGE_RATELIMIT_REDIS_ERROR bucket={} failingOpen={}: {}",
                                            rule.bucket(), failOpenOnRedisError, error.getMessage());
                                }
                                return Mono.just(failOpenOnRedisError ? 1L : -rule.windowSeconds() * 1000L);
                            })
                            .flatMap(count -> count >= 0
                                    ? allow(chain, exchange, rule)
                                    : deny(exchange, rule, -count));
                });
    }

    /** First configured rule matching method+path, or null when unrestricted. */
    private static Rule matchRule(ServerWebExchange exchange) {
        HttpMethod method = exchange.getRequest().getMethod();
        var path = exchange.getRequest().getPath().pathWithinApplication();
        for (Rule rule : RULES) {
            if (rule.method().equals(method) && rule.path().matches(path)) {
                return rule;
            }
        }
        return null;
    }

    /** Bucket identity: validated auth subject when present, else socket IP. */
    private String bucketIdentifier(ServerWebExchange exchange) {
        String subject = "anon";
        com.bhukkad.common.security.PlatformJwtValidator validator = jwtValidatorProvider.getIfAvailable();
        String authorization = exchange.getRequest().getHeaders().getFirst("Authorization");
        if (validator != null && authorization != null && authorization.startsWith("Bearer ")) {
            try {
                subject = validator.validate(authorization.substring(7))
                        .map(principal -> String.valueOf(principal.userId()))
                        .orElse("anon");
            } catch (Exception ignored) {
                subject = "anon"; // best-effort abuse key, never a security decision
            }
        }
        return subject + "|" + clientIp(exchange);
    }

    private static String clientIp(ServerWebExchange exchange) {
        // X-Forwarded-For is client-spoofable and untrusted at this edge;
        // only the observed socket address is used (see WebRateLimitKeyResolver
        // for the same policy on the service side).
        InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
        return remote == null || remote.getAddress() == null
                ? "unknown" : remote.getAddress().getHostAddress();
    }

    private Mono<Void> allow(GatewayFilterChain chain, ServerWebExchange exchange, Rule rule) {
        count("ratelimit_allowed", rule.bucket());
        return chain.filter(exchange);
    }

    private Mono<Void> deny(ServerWebExchange exchange, Rule rule, long retryAfterMillis) {
        count("ratelimit_denied", rule.bucket());
        long retryAfterSeconds = Math.max(1, (retryAfterMillis + 999) / 1000);
        exchange.getResponse().getHeaders().add("Retry-After", String.valueOf(retryAfterSeconds));
        return EdgeApiErrors.write(exchange, HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                "too many requests for " + rule.bucket());
    }

    private void count(String metric, String bucket) {
        MeterRegistry meters = meterRegistryProvider.getIfAvailable();
        if (meters != null) {
            meters.counter(metric, "bucket", bucket).increment();
        }
    }
}

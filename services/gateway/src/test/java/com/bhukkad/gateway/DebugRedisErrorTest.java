package com.bhukkad.gateway;

import com.bhukkad.common.ratelimit.RedisRateLimitService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Flux;

import java.net.InetSocketAddress;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DebugRedisErrorTest {
    @Test
    void debugRedisError() {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText("return 1");
        
        // Test with any()
        when(redis.execute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(Flux.error(new RuntimeException("connection refused")));
        
        Object result = redis.execute(script, List.of("key1"), List.of("arg1"));
        System.out.println("RESULT: " + result);
        System.out.println("RESULT_CLASS: " + (result != null ? result.getClass() : "null"));
        
        if (result instanceof Flux) {
            ((Flux<?>) result).subscribe(
                    v -> System.out.println("VALUE: " + v),
                    e -> System.out.println("ERROR: " + e)
            );
        }
    }
}

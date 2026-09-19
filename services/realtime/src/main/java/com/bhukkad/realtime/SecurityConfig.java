package com.bhukkad.realtime;

import com.bhukkad.common.security.ReactivePlatformJwtAuthFilter;
import com.bhukkad.common.security.ReactiveServiceJwtAuthFilter;
import com.bhukkad.realtime.config.LiveProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * Reactive security for the realtime service: JWT (platform + service)
 * via the platform-lib reactive filters, stateless sessions.
 *
 * <p>The SSE surface is unauthenticated — it is the public product entry point
 * for order tracking, so {@code /api/v1/live/stream/**} is permitted alongside
 * the platform probes.</p>
 */
@Configuration
@EnableWebFluxSecurity
@EnableMethodSecurity
@EnableConfigurationProperties({
        LiveProperties.class
})
public class SecurityConfig {

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http,
                                                         ObjectProvider<ReactivePlatformJwtAuthFilter> jwtAuthFilter,
                                                         ObjectProvider<ReactiveServiceJwtAuthFilter> serviceJwtAuthFilter) {
        http
                .csrf(csrf -> csrf.disable())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .exceptionHandling(ex -> ex.authenticationEntryPoint(restAuthenticationEntryPoint()))
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers("/health/**", "/actuator/**", "/api/v1/health/**").permitAll()
                        .pathMatchers("/api/v1/live/stream/**").permitAll()
                        .pathMatchers("/error").permitAll()
                        .anyExchange().authenticated());

        // Service-to-service JWT filter (must run before user JWT filter)
        ReactiveServiceJwtAuthFilter serviceFilter = serviceJwtAuthFilter.getIfAvailable();
        if (serviceFilter != null) {
            http.addFilterBefore(serviceFilter, SecurityWebFiltersOrder.AUTHENTICATION);
        }

        ReactivePlatformJwtAuthFilter filter = jwtAuthFilter.getIfAvailable();
        if (filter != null) {
            http.addFilterBefore(filter, SecurityWebFiltersOrder.AUTHENTICATION);
        }
        return http.build();
    }

    @Bean
    public ServerAuthenticationEntryPoint restAuthenticationEntryPoint() {
        return (exchange, ex) -> {
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
            String body = "{\"code\":\"UNAUTHORIZED\",\"message\":\"Authentication required\"}";
            return exchange.getResponse().writeWith(
                    Mono.just(exchange.getResponse().bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8))));
        };
    }
}

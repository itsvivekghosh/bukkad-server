package com.bhukkad.commerce.config;

import com.bhukkad.common.security.PlatformJwtAuthFilter;
import com.bhukkad.common.security.PlatformJwtProperties;
import com.bhukkad.common.security.ServiceAuthProperties;
import com.bhukkad.common.security.ServiceJwtAuthFilter;
import com.bhukkad.common.web.SecurityHeadersFilter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.nio.charset.StandardCharsets;

/**
 * Single security filter chain for the merged commerce service.
 *
 * <p>Before the 16→5 consolidation this service was three independent Spring
 * applications (order, payment, delivery), each with its own
 * {@code SecurityConfig} exposing a {@code securityFilterChain} bean. Copying
 * all three into one application produced three beans of the same name and —
 * worse — only the first {@code SecurityFilterChain} would ever be applied by
 * Spring Security, silently dropping the other two matcher sets.
 *
 * <p>This class is the union of all three permitAll() surfaces, so no
 * previously-public endpoint became authenticated by the merge:
 * <ul>
 *   <li>order: {@code GET /api/v1/coupons/active}</li>
 *   <li>payment: {@code /api/v1/webhook/**}, {@code /api/v1/payments/webhooks/**}
 *       (authenticated by HMAC signature inside the controllers, not customer auth)</li>
 *   <li>delivery: {@code GET /api/v1/serviceability/**}</li>
 * </ul>
 * Everything else stays {@code authenticated()} with method-level
 * {@code @PreAuthorize} ownership/role checks unchanged.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties({PlatformJwtProperties.class, ServiceAuthProperties.class})
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   SecurityHeadersFilter securityHeadersFilter,
                                                   ObjectProvider<ServiceJwtAuthFilter> serviceJwtAuthFilter,
                                                   ObjectProvider<PlatformJwtAuthFilter> jwtAuthFilter)
            throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(ex -> ex.authenticationEntryPoint(restAuthenticationEntryPoint()))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/health/**", "/actuator/**", "/api/v1/health/**").permitAll()
                        // from order: active-coupon lookup drives the storefront banner.
                        .requestMatchers(HttpMethod.GET, "/api/v1/coupons/active").permitAll()
                        // from payment: external provider callbacks.
                        .requestMatchers("/api/v1/webhook/**").permitAll()
                        .requestMatchers("/api/v1/payments/webhooks/**").permitAll()
                        // from delivery: pre-order serviceability check is public.
                        .requestMatchers(HttpMethod.GET, "/api/v1/serviceability/**").permitAll()
                        // Unauthenticated error forward (404s/401s to /error) must keep
                        // their real status — otherwise MVC "no handler" 404s surface as 401.
                        .requestMatchers("/error").permitAll()
                        // Controllers that return Mono/Reactive types (PaymentController#pay)
                        // finish their body on an ASYNC dispatch, AFTER the REQUEST dispatch
                        // already passed authorizeHttpRequests. Re-asserting
                        // authenticated() on that dispatch has no SecurityContext and
                        // answers 401 — the client saw 401 for a payment that had ALREADY
                        // settled server-side. Authorization is decided on the initial
                        // REQUEST dispatch; async/error redispatches must not re-check it.
                        .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ASYNC,
                                jakarta.servlet.DispatcherType.ERROR).permitAll()
                        .anyRequest().authenticated());

        // Security headers must run first
        http.addFilterBefore(securityHeadersFilter, UsernamePasswordAuthenticationFilter.class);

        // Service-to-service JWT filter (must run before user JWT filter)
        ServiceJwtAuthFilter serviceFilter = serviceJwtAuthFilter.getIfAvailable();
        if (serviceFilter != null) {
            http.addFilterBefore(serviceFilter, UsernamePasswordAuthenticationFilter.class);
        }

        PlatformJwtAuthFilter filter = jwtAuthFilter.getIfAvailable();
        if (filter != null) {
            http.addFilterBefore(filter, UsernamePasswordAuthenticationFilter.class);
        }
        return http.build();
    }

    @Bean
    public AuthenticationEntryPoint restAuthenticationEntryPoint() {
        return (request, response, authException) -> {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write("{\"code\":\"UNAUTHORIZED\",\"message\":\"Authentication required\"}");
        };
    }
}

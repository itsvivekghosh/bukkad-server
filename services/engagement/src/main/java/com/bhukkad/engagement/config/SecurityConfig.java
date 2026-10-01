package com.bhukkad.engagement.config;

import com.bhukkad.common.security.PlatformJwtAuthFilter;
import com.bhukkad.common.security.PlatformJwtProperties;
import com.bhukkad.common.security.ServiceAuthProperties;
import com.bhukkad.common.security.ServiceJwtAuthFilter;
import com.bhukkad.common.web.SecurityHeadersFilter;
import com.bhukkad.engagement.realtime.config.LiveProperties;
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
 * Single security filter chain for the merged engagement service
 * (social + growth + referral + survey + notification + realtime).
 *
 * <p>Before the consolidation each of the six source services declared its own
 * {@code SecurityConfig} named {@code securityConfig}. Merged into one
 * application they collided on bean name at context refresh. They were not
 * merely duplicates either — each contributed a different set of
 * {@code permitAll()} endpoints, and only the first chain would ever have been
 * applied, so dropping the others silently would have locked down public
 * endpoints. This class is the union:
 *
 * <ul>
 *   <li>growth: {@code GET /api/v1/campaigns/**} — the public storefront campaign banner</li>
 *   <li>social: {@code GET /api/v1/social/feed/**} and {@code GET /api/v1/social/posts/**}
 *       — public browse surfaces</li>
 *   <li>realtime: {@code /api/v1/live/stream/**} — the public order-tracking SSE entry
 *       point (the controllers return {@code Flux<ServerSentEvent>}, which Spring
 *       MVC adapts on the servlet stack, so the servlet chain below is the one
 *       that actually governs it)</li>
 * </ul>
 *
 * <p>The old realtime {@code SecurityConfig} was a WebFlux
 * {@code SecurityWebFilterChain}. Engagement runs on the servlet stack
 * (spring-boot-starter-web wins over webflux for application type), so that
 * chain was inert; its permitted paths are folded in here.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties({PlatformJwtProperties.class, ServiceAuthProperties.class, LiveProperties.class})
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   SecurityHeadersFilter securityHeadersFilter,
                                                   ObjectProvider<PlatformJwtAuthFilter> jwtAuthFilter,
                                                   ObjectProvider<ServiceJwtAuthFilter> serviceJwtAuthFilter)
            throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(ex -> ex.authenticationEntryPoint(restAuthenticationEntryPoint()))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/health/**", "/actuator/**", "/api/v1/health/**").permitAll()
                        // from realtime: public order-tracking SSE.
                        .requestMatchers("/api/v1/live/stream/**").permitAll()
                        // from growth: public campaign banner.
                        .requestMatchers(HttpMethod.GET, "/api/v1/campaigns/**").permitAll()
                        // from social: public feed + post browse.
                        .requestMatchers(HttpMethod.GET, "/api/v1/social/feed/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/social/posts/**").permitAll()
                        // from referral: public code validation (called before signup).
                        .requestMatchers("/api/v1/referrals/validate/**").permitAll()
                        // Keep the real status on the error forward, otherwise MVC
                        // "no handler" 404s surface as 401.
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated());

        http.addFilterBefore(securityHeadersFilter, UsernamePasswordAuthenticationFilter.class);

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

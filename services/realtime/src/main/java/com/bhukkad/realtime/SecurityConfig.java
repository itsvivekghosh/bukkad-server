package com.bhukkad.realtime;

import com.bhukkad.common.security.PlatformJwtAuthFilter;
import com.bhukkad.common.security.PlatformJwtProperties;
import com.bhukkad.common.security.ServiceAuthProperties;
import com.bhukkad.common.security.ServiceJwtAuthFilter;
import com.bhukkad.common.web.SecurityHeadersFilter;
import com.bhukkad.realtime.config.LiveProperties;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
 * Servlet security for the realtime service, mirroring the
 * {@code admin-analytics} / {@code order} pattern: JWT (platform + service)
 * via the platform-lib filters, stateless sessions and the standard
 * {@code /health} / {@code /actuator} permits.
 *
 * <p>The SSE surface is unauthenticated — it is the public product entry point
 * for order tracking, so {@code /api/v1/live/stream/**} is permitted alongside
 * the platform probes.</p>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties({
        PlatformJwtProperties.class,
        ServiceAuthProperties.class,
        LiveProperties.class
})
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   SecurityHeadersFilter securityHeadersFilter,
                                                   ObjectProvider<PlatformJwtAuthFilter> jwtAuthFilter,
                                                   ObjectProvider<ServiceJwtAuthFilter> serviceJwtAuthFilter)
            throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(basic -> {})
                .formLogin(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(ex -> ex.authenticationEntryPoint(restAuthenticationEntryPoint()))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/health/**", "/actuator/**").permitAll()
                        // Unauthenticated error forward (404s/401s to /error) must keep
                        // their real status — otherwise MVC "no handler" 404s surface as 401.
                        .requestMatchers("/error").permitAll()
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

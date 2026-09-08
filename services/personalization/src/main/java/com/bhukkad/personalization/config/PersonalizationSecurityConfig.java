package com.bhukkad.personalization.config;

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
 * Personalization's security wiring. Without an explicit chain the Boot
 * default locked EVERY endpoint behind HTTP Basic — the customer-facing
 * recommendation self-surface then answered 401 with an empty body for valid
 * JWTs. Mirrors the platform pattern used by every other service: stateless,
 * CSRF off, platform JWT filter authenticating Bearer tokens, mesh token
 * filter for internal paths, JSON 401 entry point.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties({PlatformJwtProperties.class, ServiceAuthProperties.class})
public class PersonalizationSecurityConfig {

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
                        .requestMatchers("/health/**", "/actuator/**").permitAll()
                        // Unauthenticated error forward (404s/401s to /error) must keep
                        // their real status — otherwise MVC "no handler" 404s surface as 401.
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

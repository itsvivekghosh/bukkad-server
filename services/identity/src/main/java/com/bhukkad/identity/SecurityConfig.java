package com.bhukkad.identity;

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
 * Identity security chain (feature #6): {@code /api/v1/internal/**} now
 * REQUIRES {@code ROLE_SERVICE} — the {@link ServiceJwtAuthFilter} rejects
 * absent/invalid {@code X-Service-Token} headers with 401 before this rule
 * ever sees the request, so the rule backstops (defence in depth) rather
 * than carrying the enforcement alone. The token-introspection oracle is
 * therefore no longer callable by any ordinary customer JWT. Auth + actuator
 * health + the JWKS distribution endpoint stay public.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties({PlatformJwtProperties.class, ServiceAuthProperties.class,
        com.bhukkad.identity.ratelimit.LoginLockoutProperties.class})
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
                        // Aggregate API docs (springdoc) — public metadata.
                        .requestMatchers("/swagger-ui/**", "/swagger-ui.html",
                                "/v3/api-docs/**", "/api-docs/**").permitAll()
                        // identity issues the tokens: register/login are public.
                        .requestMatchers("/api/v1/auth/**").permitAll()
                        // Public platform endpoints (serve by identity)
                        .requestMatchers("/api/v1/platform/**").permitAll()
                        .requestMatchers("/api/v1/membership/**").permitAll()
                        // JWKS distribution — public keys only; every service's
                        // PlatformJwtValidator fetches this to verify RS256 tokens.
                        .requestMatchers("/.well-known/jwks.json", "/well-known/jwks.json").permitAll()
                        // Service-to-service surface: ROLE_SERVICE only. The
                        // ServiceJwtAuthFilter 401s anything without a valid
                        // X-Service-Token first; this rule rejects whatever
                        // else reaches it (e.g. a user JWT) with 403.
                        .requestMatchers("/api/v1/internal/**").hasRole("SERVICE")
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

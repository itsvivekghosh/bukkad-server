package com.bhukkad.config;

import com.bhukkad.logging.RequestLoggingFilter;
import com.bhukkad.security.CustomUserDetailsService;
import com.bhukkad.security.JwtAuthenticationFilter;
import com.bhukkad.security.PrometheusAuthFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private static final String V1 = ApiPaths.V1_PREFIX;

    private final CustomUserDetailsService userDetailsService;
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final RequestLoggingFilter requestLoggingFilter;
    private final PrometheusAuthFilter prometheusAuthFilter;

    @Value("${app.debug:false}")
    private boolean debugMode;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> {

                    // ==================== PUBLIC - No Auth ====================

                    // Auth
                    auth.requestMatchers(V1 + "/auth/**").permitAll();
                    auth.requestMatchers(V1 + "/payments/webhooks/**").permitAll();

                    // Health
                    auth.requestMatchers(V1 + "/health/**").permitAll();
                    auth.requestMatchers(
                        "/actuator/health",
                        "/actuator/health/**",
                        "/actuator/info"
                    ).permitAll();
                    if (debugMode) {
                        auth.requestMatchers("/actuator/prometheus").permitAll();
                    } else {
                        auth.requestMatchers("/actuator/prometheus").hasRole("ADMIN");
                    }

                    // Swagger
                    auth.requestMatchers(
                            "/swagger-ui/**",
                            "/swagger-ui.html",
                            "/v3/api-docs/**",
                            "/v3/api-docs",
                            "/webjars/**",
                            "/favicon.ico"
                    ).permitAll();

                    // Restaurants - Public GET
                    auth.requestMatchers(HttpMethod.GET, V1 + "/restaurants/public/**").permitAll();

                    // Cuisines - Public GET
                    auth.requestMatchers(HttpMethod.GET, V1 + "/cuisines/**").permitAll();

                    // Menu - Public GET (all GET requests)
                    auth.requestMatchers(HttpMethod.GET, V1 + "/menu/items/**").permitAll();
                    auth.requestMatchers(HttpMethod.GET, V1 + "/menu/items/search").permitAll();
                    auth.requestMatchers(HttpMethod.GET, V1 + "/menu/items/restaurant/**").permitAll();
                    auth.requestMatchers(HttpMethod.GET, V1 + "/menu/items/category/**").permitAll();
                    auth.requestMatchers(HttpMethod.GET, V1 + "/menu/categories/**").permitAll();

                    // Coupons - Public GET
                    auth.requestMatchers(HttpMethod.GET, V1 + "/coupons/active").permitAll();

                    // Reviews - Public GET
                    auth.requestMatchers(HttpMethod.GET, V1 + "/reviews/restaurant/**").permitAll();
                    auth.requestMatchers(HttpMethod.GET, V1 + "/reviews/menu-items/**").permitAll();

                    // Unified search - Public GET
                    auth.requestMatchers(HttpMethod.GET, V1 + "/search").permitAll();

                    // Platform status - Public GET
                    auth.requestMatchers(HttpMethod.GET, V1 + "/platform/status").permitAll();
                    auth.requestMatchers(HttpMethod.GET, V1 + "/platform/cities").permitAll();
                    auth.requestMatchers(HttpMethod.GET, V1 + "/platform/tenants/**").permitAll();

                    // Serviceability & home feed - Public GET
                    auth.requestMatchers(HttpMethod.GET, V1 + "/serviceability/**").permitAll();
                    auth.requestMatchers(HttpMethod.GET, V1 + "/home/**").permitAll();

                    // Cache - Dev public, Prod admin
                    if (debugMode) {
                        auth.requestMatchers(V1 + "/cache/**").permitAll();
                    } else {
                        auth.requestMatchers(V1 + "/cache/**").hasRole("ADMIN");
                    }

                    // ==================== CUSTOMER ====================
                    auth.requestMatchers(V1 + "/customers/**").hasRole("CUSTOMER");
                    auth.requestMatchers(V1 + "/cart/**").hasRole("CUSTOMER");
                    auth.requestMatchers(V1 + "/orders/customer/**").hasRole("CUSTOMER");
                    auth.requestMatchers(V1 + "/payments/orders/**").hasRole("CUSTOMER");
                    auth.requestMatchers(V1 + "/coupons/validate").hasRole("CUSTOMER");
                    auth.requestMatchers(HttpMethod.POST, V1 + "/reviews").hasRole("CUSTOMER");
                    auth.requestMatchers(HttpMethod.POST, V1 + "/reviews/menu-items").hasRole("CUSTOMER");
                    auth.requestMatchers(HttpMethod.GET, V1 + "/reviews/my-reviews").hasRole("CUSTOMER");
                    auth.requestMatchers(HttpMethod.DELETE, V1 + "/reviews/**").hasRole("CUSTOMER");

                    // ==================== RESTAURANT OWNER ====================
                    auth.requestMatchers(V1 + "/restaurants/owner/**").hasRole("RESTAURANT_OWNER");
                    auth.requestMatchers(V1 + "/restaurants/onboarding/**").hasRole("RESTAURANT_OWNER");
                    auth.requestMatchers(HttpMethod.POST, V1 + "/menu/**").hasRole("RESTAURANT_OWNER");
                    auth.requestMatchers(HttpMethod.PUT, V1 + "/menu/**").hasRole("RESTAURANT_OWNER");
                    auth.requestMatchers(HttpMethod.DELETE, V1 + "/menu/**").hasRole("RESTAURANT_OWNER");
                    auth.requestMatchers(V1 + "/orders/restaurant/**").hasRole("RESTAURANT_OWNER");

                    // ==================== DELIVERY AGENT ====================
                    auth.requestMatchers(V1 + "/delivery/**").hasRole("DELIVERY_AGENT");
                    auth.requestMatchers(V1 + "/orders/delivery/**").hasRole("DELIVERY_AGENT");

                    // ==================== ADMIN ====================
                    auth.requestMatchers(V1 + "/admin/**").hasRole("ADMIN");
                    auth.requestMatchers(HttpMethod.POST, V1 + "/coupons").hasAnyRole("ADMIN", "RESTAURANT_OWNER");
                    auth.requestMatchers(HttpMethod.PUT, V1 + "/coupons/**").hasRole("ADMIN");
                    auth.requestMatchers(HttpMethod.DELETE, V1 + "/coupons/**").hasRole("ADMIN");

                    // WebSocket handshake (JWT validated in interceptor)
                    auth.requestMatchers("/ws/**", "/ws-native/**").permitAll();

                    // Live order streams (SSE)
                    auth.requestMatchers(V1 + "/orders/stream/kitchen/**").hasRole("RESTAURANT_OWNER");
                    auth.requestMatchers(V1 + "/orders/stream/rider").hasRole("DELIVERY_AGENT");
                    auth.requestMatchers(V1 + "/orders/stream/customer/**").hasRole("CUSTOMER");

                    // ==================== ERROR DISPATCH ====================
                    // Spring MVC FORWARDs unmatched/failed requests to /error. Because Spring Boot
                    // registers filters for the ERROR dispatcher type by default, Spring Security
                    // re-evaluates that forward as a fresh request with an empty SecurityContext
                    // (session policy is STATELESS and JwtAuthenticationFilter, being a
                    // OncePerRequestFilter, has already run for the original dispatch). Without an
                    // explicit permitAll the forward falls through to anyRequest().authenticated(),
                    // is treated as anonymous, and the real status (404/500) is masked as 403.
                    auth.requestMatchers("/error").permitAll();

                    // ==================== ALL OTHERS ====================
                    auth.anyRequest().authenticated();
                })
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )
                .authenticationProvider(authenticationProvider())
                .addFilterBefore(prometheusAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(requestLoggingFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider authProvider = new DaoAuthenticationProvider();
        authProvider.setUserDetailsService(userDetailsService);
        authProvider.setPasswordEncoder(passwordEncoder());
        return authProvider;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }
}
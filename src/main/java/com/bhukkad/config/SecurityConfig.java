package com.bhukkad.config;

import com.bhukkad.logging.RequestLoggingFilter;
import com.bhukkad.security.CustomUserDetailsService;
import com.bhukkad.security.JwtAuthenticationFilter;
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

    private final CustomUserDetailsService userDetailsService;
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final RequestLoggingFilter requestLoggingFilter;

    @Value("${app.debug:false}")
    private boolean debugMode;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> {

                    // ==================== PUBLIC - No Auth ====================

                    // Auth
                    auth.requestMatchers("/api/auth/**").permitAll();

                    // Health
                    auth.requestMatchers("/api/health/**").permitAll();

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
                    auth.requestMatchers(HttpMethod.GET, "/api/restaurants/public/**").permitAll();

                    // Cuisines - Public GET
                    auth.requestMatchers(HttpMethod.GET, "/api/cuisines/**").permitAll();

                    // Menu - Public GET (all GET requests)
                    auth.requestMatchers(HttpMethod.GET, "/api/menu/items/**").permitAll();
                    auth.requestMatchers(HttpMethod.GET, "/api/menu/items/search").permitAll();
                    auth.requestMatchers(HttpMethod.GET, "/api/menu/items/restaurant/**").permitAll();
                    auth.requestMatchers(HttpMethod.GET, "/api/menu/items/category/**").permitAll();
                    auth.requestMatchers(HttpMethod.GET, "/api/menu/categories/**").permitAll();

                    // Coupons - Public GET
                    auth.requestMatchers(HttpMethod.GET, "/api/coupons/active").permitAll();

                    // Reviews - Public GET
                    auth.requestMatchers(HttpMethod.GET, "/api/reviews/restaurant/**").permitAll();

                    // Cache - Dev public, Prod admin
                    if (debugMode) {
                        auth.requestMatchers("/api/cache/**").permitAll();
                    } else {
                        auth.requestMatchers("/api/cache/**").hasRole("ADMIN");
                    }

                    // ==================== CUSTOMER ====================
                    auth.requestMatchers("/api/customers/**").hasRole("CUSTOMER");
                    auth.requestMatchers("/api/cart/**").hasRole("CUSTOMER");
                    auth.requestMatchers("/api/orders/customer/**").hasRole("CUSTOMER");
                    auth.requestMatchers("/api/coupons/validate").hasRole("CUSTOMER");
                    auth.requestMatchers(HttpMethod.POST, "/api/reviews").hasRole("CUSTOMER");
                    auth.requestMatchers(HttpMethod.GET, "/api/reviews/my-reviews").hasRole("CUSTOMER");
                    auth.requestMatchers(HttpMethod.DELETE, "/api/reviews/**").hasRole("CUSTOMER");

                    // ==================== RESTAURANT OWNER ====================
                    auth.requestMatchers("/api/restaurants/owner/**").hasRole("RESTAURANT_OWNER");
                    auth.requestMatchers(HttpMethod.POST, "/api/menu/**").hasRole("RESTAURANT_OWNER");
                    auth.requestMatchers(HttpMethod.PUT, "/api/menu/**").hasRole("RESTAURANT_OWNER");
                    auth.requestMatchers(HttpMethod.DELETE, "/api/menu/**").hasRole("RESTAURANT_OWNER");
                    auth.requestMatchers("/api/orders/restaurant/**").hasRole("RESTAURANT_OWNER");

                    // ==================== DELIVERY AGENT ====================
                    auth.requestMatchers("/api/delivery/**").hasRole("DELIVERY_AGENT");
                    auth.requestMatchers("/api/orders/delivery/**").hasRole("DELIVERY_AGENT");

                    // ==================== ADMIN ====================
                    auth.requestMatchers("/api/admin/**").hasRole("ADMIN");
                    auth.requestMatchers(HttpMethod.POST, "/api/coupons").hasAnyRole("ADMIN", "RESTAURANT_OWNER");
                    auth.requestMatchers(HttpMethod.PUT, "/api/coupons/**").hasRole("ADMIN");
                    auth.requestMatchers(HttpMethod.DELETE, "/api/coupons/**").hasRole("ADMIN");

                    // ==================== ALL OTHERS ====================
                    auth.anyRequest().authenticated();
                })
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )
                .authenticationProvider(authenticationProvider())
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
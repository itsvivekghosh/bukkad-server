package com.bhukkad.config;

import com.bhukkad.logging.RequestLoggingFilter;
import com.bhukkad.security.CustomUserDetailsService;
import com.bhukkad.security.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
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

    // All swagger and public paths
    private static final String[] PUBLIC_PATHS = {
            "/api/auth/**",
            "/api/health/**",
            // Swagger UI paths
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/swagger-ui/index.html",
            // OpenAPI paths
            "/v3/api-docs/**",
            "/v3/api-docs",
            "/v3/api-docs/swagger-config",
            "/api-docs/**",
            "/api-docs",
            // Webjars
            "/webjars/**",
            "/webjars/swagger-ui/**",
            // Swagger resources
            "/swagger-resources/**",
            "/configuration/ui",
            "/configuration/security",
            // Favicon
            "/favicon.ico"
    };

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth

                        // Public paths
                        .requestMatchers(PUBLIC_PATHS).permitAll()

                        // Public GET restaurant endpoints
                        .requestMatchers(HttpMethod.GET, "/api/restaurants/public/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/cuisines/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/menu/items/restaurant/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/menu/categories/restaurant/**").permitAll()

                        // Customer endpoints
                        .requestMatchers("/api/customers/**").hasRole("CUSTOMER")
                        .requestMatchers("/api/cart/**").hasRole("CUSTOMER")
                        .requestMatchers("/api/orders/customer/**").hasRole("CUSTOMER")
                        .requestMatchers("/api/reviews/**").hasRole("CUSTOMER")

                        // Restaurant Owner endpoints
                        .requestMatchers("/api/restaurants/owner/**").hasRole("RESTAURANT_OWNER")
                        .requestMatchers("/api/menu/**").hasRole("RESTAURANT_OWNER")
                        .requestMatchers("/api/orders/restaurant/**").hasRole("RESTAURANT_OWNER")

                        // Delivery Agent endpoints
                        .requestMatchers("/api/delivery/**").hasRole("DELIVERY_AGENT")
                        .requestMatchers("/api/orders/delivery/**").hasRole("DELIVERY_AGENT")

                        // Admin endpoints
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")

                        .requestMatchers("/api/cache/**").hasRole("ADMIN") // Add to permitAll or admin-only section
                        .requestMatchers("/api/cache/**").permitAll() // Or for dev, add to permitAll

                        // All other requests must be authenticated
                        .anyRequest().authenticated()

                )
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )
                .authenticationProvider(authenticationProvider())
                // ORDER MATTERS: RequestLoggingFilter -> JwtFilter -> UsernamePasswordFilter
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
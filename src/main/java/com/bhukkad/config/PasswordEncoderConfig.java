package com.bhukkad.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Provides the shared {@link PasswordEncoder} bean.
 *
 * <p>Defined in its own configuration class (rather than in
 * {@link SecurityConfig}) to break a circular reference: {@link SecurityConfig}
 * depends on {@code OAuth2LoginSuccessHandler}, which depends on the
 * {@code PasswordEncoder} bean. If the encoder were declared inside
 * {@link SecurityConfig}, Spring could not create it while
 * {@link SecurityConfig} was still being constructed.
 */
@Configuration
public class PasswordEncoderConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
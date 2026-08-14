package com.bhukkad.config;

import com.bhukkad.logging.RequestLoggingFilter;
import com.bhukkad.security.CustomUserDetailsService;
import com.bhukkad.security.JwtAuthenticationFilter;
import com.bhukkad.security.PrometheusAuthFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.security.config.annotation.web.configurers.CsrfConfigurer;
import org.springframework.security.config.annotation.web.configurers.SessionManagementConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.DefaultSecurityFilterChain;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;

@ExtendWith(MockitoExtension.class)
class SecurityConfigTest {

    @Mock
    private CustomUserDetailsService userDetailsService;

    @Mock
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @Mock
    private RequestLoggingFilter requestLoggingFilter;

    @Mock
    private PrometheusAuthFilter prometheusAuthFilter;

    @InjectMocks
    private SecurityConfig securityConfig;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(securityConfig, "debugMode", false);
    }

    @Test
    void passwordEncoder_returnsBCrypt() {
        PasswordEncoder encoder = securityConfig.passwordEncoder();

        assertInstanceOf(BCryptPasswordEncoder.class, encoder);
        assertTrue(encoder.matches("secret", encoder.encode("secret")));
    }

    @Test
    void authenticationProvider_usesUserDetailsServiceAndPasswordEncoder() {
        AuthenticationProvider provider = securityConfig.authenticationProvider();

        assertInstanceOf(DaoAuthenticationProvider.class, provider);
        DaoAuthenticationProvider dao = (DaoAuthenticationProvider) provider;
        assertSame(userDetailsService, ReflectionTestUtils.getField(dao, "userDetailsService"));
        assertInstanceOf(BCryptPasswordEncoder.class, ReflectionTestUtils.getField(dao, "passwordEncoder"));
    }

    @Test
    void authenticationManager_delegatesToConfiguration() throws Exception {
        AuthenticationConfiguration configuration = mock(AuthenticationConfiguration.class);
        AuthenticationManager manager = mock(AuthenticationManager.class);
        when(configuration.getAuthenticationManager()).thenReturn(manager);

        AuthenticationManager result = securityConfig.authenticationManager(configuration);

        assertSame(manager, result);
        verify(configuration).getAuthenticationManager();
    }

    @Test
    void securityFilterChain_whenDebugDisabled() throws Exception {
        ReflectionTestUtils.setField(securityConfig, "debugMode", false);
        SecurityFilterChain chain = invokeSecurityFilterChain();
        assertNotNull(chain);
    }

    @Test
    void securityFilterChain_whenDebugEnabled() throws Exception {
        ReflectionTestUtils.setField(securityConfig, "debugMode", true);
        SecurityFilterChain chain = invokeSecurityFilterChain();
        assertNotNull(chain);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private SecurityFilterChain invokeSecurityFilterChain() throws Exception {
        HttpSecurity http = mock(HttpSecurity.class);

        when(http.csrf(any())).thenAnswer(invocation -> {
            Customizer customizer = invocation.getArgument(0);
            CsrfConfigurer<HttpSecurity> csrf = mock(CsrfConfigurer.class, RETURNS_DEEP_STUBS);
            customizer.customize(csrf);
            return http;
        });

        AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry registry =
                mock(AuthorizeHttpRequestsConfigurer.AuthorizationManagerRequestMatcherRegistry.class, RETURNS_DEEP_STUBS);

        when(http.authorizeHttpRequests(any())).thenAnswer(invocation -> {
            Customizer customizer = invocation.getArgument(0);
            customizer.customize(registry);
            return http;
        });

        when(http.sessionManagement(any())).thenAnswer(invocation -> {
            Customizer customizer = invocation.getArgument(0);
            SessionManagementConfigurer<HttpSecurity> session =
                    mock(SessionManagementConfigurer.class, RETURNS_DEEP_STUBS);
            customizer.customize(session);
            return http;
        });

        when(http.authenticationProvider(any())).thenReturn(http);
        when(http.addFilterBefore(any(), any())).thenReturn(http);

        DefaultSecurityFilterChain filterChain = mock(DefaultSecurityFilterChain.class);
        when(http.build()).thenReturn(filterChain);

        SecurityFilterChain result = securityConfig.securityFilterChain(http);
        assertSame(filterChain, result);
        verify(http).authenticationProvider(any());
        verify(http, times(3)).addFilterBefore(any(), any());
        return result;
    }
}

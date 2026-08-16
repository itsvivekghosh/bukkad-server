package com.bhukkad.security;

import com.bhukkad.entity.User;
import com.bhukkad.logging.LoggingConstants;
import com.bhukkad.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

    @Mock
    private JwtTokenProvider jwtTokenProvider;
    @Mock
    private CustomUserDetailsService userDetailsService;
    @Mock
    private UserRepository userRepository;
    @Mock
    private AuthTokenService authTokenService;
    @Mock
    private FilterChain filterChain;

    private JwtAuthenticationFilter filter;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        filter = new JwtAuthenticationFilter(jwtTokenProvider, userDetailsService, userRepository, authTokenService);
        lenient().when(authTokenService.isAccessTokenBlacklisted(any())).thenReturn(false);
        lenient().when(jwtTokenProvider.isRefreshToken(any())).thenReturn(false);
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        SecurityContextHolder.clearContext();
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        MDC.clear();
    }

    @Test
    void doFilter_noAuthorizationHeader() throws Exception {
        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verifyNoInteractions(jwtTokenProvider);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void doFilter_notBearer() throws Exception {
        request.addHeader("Authorization", "Basic abc");

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verifyNoInteractions(jwtTokenProvider);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void doFilter_emptyTokenAfterBearerPrefix() throws Exception {
        request.addHeader("Authorization", "Bearer ");

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(jwtTokenProvider, never()).validateToken(any());
    }

    @Test
    void doFilter_invalidToken() throws Exception {
        request.addHeader("Authorization", "Bearer bad-token");
        when(jwtTokenProvider.validateToken("bad-token")).thenReturn(false);

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(jwtTokenProvider, never()).extractUsername(any());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void doFilter_validTokenButIsTokenValidFalse() throws Exception {
        request.addHeader("Authorization", "Bearer jwt");
        UserDetails userDetails = org.springframework.security.core.userdetails.User
                .withUsername("user@example.com").password("p").roles("CUSTOMER").build();
        when(jwtTokenProvider.validateToken("jwt")).thenReturn(true);
        when(jwtTokenProvider.extractUsername("jwt")).thenReturn("user@example.com");
        when(userDetailsService.loadUserByUsername("user@example.com")).thenReturn(userDetails);
        when(jwtTokenProvider.isTokenValid("jwt", userDetails)).thenReturn(false);

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void doFilter_fullSuccess_setsSecurityContextAndMdc() throws Exception {
        request.addHeader("Authorization", "Bearer jwt");
        UserDetails userDetails = org.springframework.security.core.userdetails.User
                .withUsername("user@example.com").password("p").roles("CUSTOMER").build();
        User user = new User();
        user.setId(42L);
        user.setEmail("user@example.com");

        when(jwtTokenProvider.validateToken("jwt")).thenReturn(true);
        when(jwtTokenProvider.extractUsername("jwt")).thenReturn("user@example.com");
        when(userDetailsService.loadUserByUsername("user@example.com")).thenReturn(userDetails);
        when(jwtTokenProvider.isTokenValid("jwt", userDetails)).thenReturn(true);
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
        assertEquals("user@example.com", SecurityContextHolder.getContext().getAuthentication().getName());
        assertEquals("user@example.com", MDC.get(LoggingConstants.USER_EMAIL));
        assertEquals("42", MDC.get(LoggingConstants.USER_ID));
        assertEquals("ROLE_CUSTOMER", MDC.get(LoggingConstants.USER_ROLE));
        assertNotNull(MDC.get(LoggingConstants.TIMESTAMP));
    }

    @Test
    void doFilter_exceptionDuringAuth_setsInvalidTokenMdc() throws Exception {
        request.addHeader("Authorization", "Bearer jwt");
        when(jwtTokenProvider.validateToken("jwt")).thenThrow(new RuntimeException("boom"));

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertEquals("INVALID_TOKEN", MDC.get(LoggingConstants.USER_ID));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void doFilter_emptyAuthorities_skipsRole() throws Exception {
        request.addHeader("Authorization", "Bearer jwt");
        UserDetails userDetails = new org.springframework.security.core.userdetails.User(
                "user@example.com", "p", Collections.emptyList());
        User user = new User();
        user.setId(7L);

        when(jwtTokenProvider.validateToken("jwt")).thenReturn(true);
        when(jwtTokenProvider.extractUsername("jwt")).thenReturn("user@example.com");
        when(userDetailsService.loadUserByUsername("user@example.com")).thenReturn(userDetails);
        when(jwtTokenProvider.isTokenValid("jwt", userDetails)).thenReturn(true);
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));

        filter.doFilter(request, response, filterChain);

        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
        assertEquals("user@example.com", MDC.get(LoggingConstants.USER_EMAIL));
        assertEquals("7", MDC.get(LoggingConstants.USER_ID));
        assertNull(MDC.get(LoggingConstants.USER_ROLE));
    }

    @Test
    void doFilter_userNotInDb_skipsUserId() throws Exception {
        request.addHeader("Authorization", "Bearer jwt");
        UserDetails userDetails = org.springframework.security.core.userdetails.User
                .withUsername("user@example.com").password("p")
                .authorities(List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))).build();

        when(jwtTokenProvider.validateToken("jwt")).thenReturn(true);
        when(jwtTokenProvider.extractUsername("jwt")).thenReturn("user@example.com");
        when(userDetailsService.loadUserByUsername("user@example.com")).thenReturn(userDetails);
        when(jwtTokenProvider.isTokenValid("jwt", userDetails)).thenReturn(true);
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.empty());

        filter.doFilter(request, response, filterChain);

        assertEquals("user@example.com", MDC.get(LoggingConstants.USER_EMAIL));
        assertEquals("ROLE_ADMIN", MDC.get(LoggingConstants.USER_ROLE));
        assertNull(MDC.get(LoggingConstants.USER_ID));
    }

    @Test
    void doFilter_mdcInnerException_doesNotFailFilter() throws Exception {
        request.addHeader("Authorization", "Bearer jwt");
        UserDetails userDetails = org.springframework.security.core.userdetails.User
                .withUsername("user@example.com").password("p").roles("CUSTOMER").build();

        when(jwtTokenProvider.validateToken("jwt")).thenReturn(true);
        when(jwtTokenProvider.extractUsername("jwt")).thenReturn("user@example.com");
        when(userDetailsService.loadUserByUsername("user@example.com")).thenReturn(userDetails);
        when(jwtTokenProvider.isTokenValid("jwt", userDetails)).thenReturn(true);
        when(userRepository.findByEmail("user@example.com")).thenThrow(new RuntimeException("db down"));

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void doFilter_usesServletMocks() throws Exception {
        HttpServletRequest httpRequest = mock(HttpServletRequest.class);
        HttpServletResponse httpResponse = mock(HttpServletResponse.class);
        when(httpRequest.getHeader("Authorization")).thenReturn(null);

        filter.doFilter(httpRequest, httpResponse, filterChain);

        verify(filterChain).doFilter(httpRequest, httpResponse);
    }
}

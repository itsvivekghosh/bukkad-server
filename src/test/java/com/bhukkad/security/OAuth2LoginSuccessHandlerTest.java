package com.bhukkad.security;

import com.bhukkad.audit.AuditService;
import com.bhukkad.entity.Customer;
import com.bhukkad.entity.User;
import com.bhukkad.repository.CustomerRepository;
import com.bhukkad.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OAuth2LoginSuccessHandlerTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private CustomerRepository customerRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private JwtTokenProvider jwtTokenProvider;
    @Mock
    private AuthTokenService authTokenService;
    @Mock
    private AuditService auditService;

    private OAuth2LoginSuccessHandler handler;

    @BeforeEach
    void setUp() {
        handler = new OAuth2LoginSuccessHandler(customerRepository,
                passwordEncoder, jwtTokenProvider, authTokenService, auditService);
    }

    private OAuth2AuthenticationToken googleAuth(Map<String, Object> attributes) {
        OAuth2User principal = new DefaultOAuth2User(
                List.of(new SimpleGrantedAuthority("ROLE_USER")), attributes, "email");
        return new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "google");
    }

    @Test
    void onAuthenticationSuccess_provisionsNewCustomerAndRedirectsWithTokens() throws Exception {
        when(customerRepository.findByEmail("new.user@gmail.com")).thenReturn(Optional.empty());
        Customer saved = new Customer();
        saved.setId(11L);
        AccountFields.setEmail(saved, "new.user@gmail.com");
        saved.setRole(User.UserRole.CUSTOMER);
        AccountFields.setPassword(saved, "encoded");
        when(customerRepository.save(any(Customer.class))).thenReturn(saved);
        when(passwordEncoder.encode(anyString())).thenReturn("encoded");
        when(jwtTokenProvider.generateAccessToken(any())).thenReturn("access.jwt");
        when(jwtTokenProvider.generateRefreshToken(any())).thenReturn("refresh.jwt");
        when(jwtTokenProvider.getRemainingValidityMs("refresh.jwt")).thenReturn(600_000L);

        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(request, response,
                googleAuth(Map.of("email", "new.user@gmail.com", "name", "New User")));

        ArgumentCaptor<Customer> captor = ArgumentCaptor.forClass(Customer.class);
        verify(customerRepository).save(captor.capture());
        assertTrue(captor.getValue().getEmailVerified(), "provider emails are verified");
        assertEquals(User.UserRole.CUSTOMER, captor.getValue().getRole());

        verify(authTokenService).storeRefreshToken(11L, "refresh.jwt", 600_000L);
        String redirected = response.getRedirectedUrl();
        assertTrue(redirected.contains("token=access.jwt"));
        assertTrue(redirected.contains("refreshToken=refresh.jwt"));
    }

    @Test
    void onAuthenticationSuccess_linksExistingUserWithoutCreatingAccount() throws Exception {
        Customer existing = new Customer();
        existing.setId(7L);
        AccountFields.setEmail(existing, "existing@gmail.com");
        existing.setActive(true);
        existing.setRole(User.UserRole.CUSTOMER);
        AccountFields.setPassword(existing, "hash");
        when(customerRepository.findByEmail("existing@gmail.com")).thenReturn(Optional.of(existing));
        when(jwtTokenProvider.generateAccessToken(any())).thenReturn("a");
        when(jwtTokenProvider.generateRefreshToken(any())).thenReturn("r");
        when(jwtTokenProvider.getRemainingValidityMs(anyString())).thenReturn(1000L);

        MockHttpServletResponse response = new MockHttpServletResponse();
        handler.onAuthenticationSuccess(new MockHttpServletRequest(), response,
                googleAuth(Map.of("email", "existing@gmail.com")));

        verify(customerRepository, never()).save(any());
        assertTrue(response.getRedirectedUrl().contains("token=a"));
    }

    @Test
    void onAuthenticationSuccess_redirectsWithErrorWhenEmailMissing() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        // "email" key present but blank — DefaultOAuth2User requires the key to exist.
        handler.onAuthenticationSuccess(new MockHttpServletRequest(), response,
                googleAuth(Map.of("email", "")));

        verify(customerRepository, never()).findByEmail(any());
        assertTrue(response.getRedirectedUrl().contains("error="));
    }
}

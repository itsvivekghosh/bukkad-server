package com.bhukkad.apikey;

import com.bhukkad.exception.UnauthorizedException;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApiKeyFilterTest {

    @Mock
    private ApiKeyService apiKeyService;
    @Mock
    private FilterChain filterChain;

    private ApiKeyFilter filter() {
        return new ApiKeyFilter(apiKeyService);
    }

    private ApiKey activeKey(Long partnerId) {
        ApiKey key = new ApiKey();
        key.setId(1L);
        key.setPartnerId(partnerId);
        key.setStatus(ApiKey.ApiKeyStatus.ACTIVE);
        return key;
    }

    @Test void noApiKeyHeader_passesThroughWithoutAuth() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/foo");
        ApiKeyFilter f = filter();
        f.doFilterInternal(request, new MockHttpServletResponse(), filterChain);
        verify(filterChain).doFilter(any(), any());
    }

    @Test void blankApiKeyHeader_passesThrough() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/foo");
        request.addHeader("X-API-Key", "   ");
        filter().doFilterInternal(request, new MockHttpServletResponse(), filterChain);
        verify(filterChain).doFilter(any(), any());
    }

    @Test void validApiKey_setsPartnerAuthentication() throws Exception {
        when(apiKeyService.validate("bhk_prefix_secret")).thenReturn(Optional.of(activeKey(42L)));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/foo");
        request.addHeader("X-API-Key", "bhk_prefix_secret");
        ApiKeyFilter f = filter();
        f.doFilterInternal(request, new MockHttpServletResponse(), filterChain);

        verify(filterChain).doFilter(any(), any());
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        assertTrue(auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_PARTNER")));
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    @Test void invalidApiKey_throwsUnauthorized() throws Exception {
        when(apiKeyService.validate("bhk_bad_secret")).thenReturn(Optional.empty());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/foo");
        request.addHeader("X-API-Key", "bhk_bad_secret");

        assertThrows(UnauthorizedException.class,
                () -> filter().doFilterInternal(request, new MockHttpServletResponse(), filterChain));
        verify(filterChain, never()).doFilter(any(), any());
    }
}

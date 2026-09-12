package com.bhukkad.growth.infrastructure.client;

import com.bhukkad.common.security.ServiceJwtAuthTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.lang.reflect.Constructor;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Mesh client behavior for referral-internal code generation: service-JWT
 * header attached when the provider is available, a null result (never a
 * locally minted second-class code) when the upstream is unreachable, and
 * the private request/response records kept bean-compatible.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReferralServiceClientTest {

    @Mock private RestClient.Builder restClientBuilder;
    @Mock private RestClient restClient;
    @Mock private RestClient.RequestBodyUriSpec postSpec;
    @Mock private RestClient.RequestBodySpec bodySpec;
    @Mock private RestClient.ResponseSpec responseSpec;
    @Mock private ObjectProvider<ServiceJwtAuthTokenProvider> authProvider;
    @Mock private ServiceJwtAuthTokenProvider tokenProvider;

    private RestClient.Builder builder;
    private Class<?> codeResponseClass;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() throws Exception {
        builder = org.mockito.Mockito.mock(RestClient.Builder.class);
        when(builder.baseUrl(anyString())).thenReturn(builder);
        when(builder.requestFactory(any())).thenReturn(builder);
        when(builder.defaultHeaders(any())).thenReturn(builder);
        when(builder.build()).thenReturn(restClient);
        when(restClient.post()).thenReturn(postSpec);
        when(postSpec.uri(anyString())).thenReturn(bodySpec);
        when(bodySpec.header(anyString(), any(String[].class))).thenReturn(bodySpec);
        when(bodySpec.body(any(Object.class))).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(responseSpec);

        codeResponseClass = Arrays.stream(ReferralServiceClient.class.getDeclaredClasses())
                .filter(k -> k.getSimpleName().equals("CodeResponse"))
                .findFirst().orElseThrow();
    }

    private Object newResponse(String referralCode) throws Exception {
        Constructor<?> ctor = codeResponseClass.getDeclaredConstructor(String.class);
        ctor.setAccessible(true);
        return ctor.newInstance(referralCode);
    }

    private ReferralServiceClient client() {
        return new ReferralServiceClient(builder, "http://referral:8084", authProvider);
    }

    @Test
    void generateCode_attachesServiceTokenAndReturnsCode() throws Exception {
        when(authProvider.getIfAvailable()).thenReturn(tokenProvider);
        when(tokenProvider.serviceToken()).thenReturn("svc.jwt.token");
        org.mockito.Mockito.doReturn(newResponse("BK42")).when(responseSpec).body(codeResponseClass);

        assertThat(client().generateCode(7L)).isEqualTo("BK42");

        verify(postSpec).uri("/api/v1/referrals/internal/code");
        verify(bodySpec).header("X-Service-Token", "svc.jwt.token");
    }

    @Test
    void generateCode_withoutTokenProvider_omitsHeader() throws Exception {
        when(authProvider.getIfAvailable()).thenReturn(null);
        org.mockito.Mockito.doReturn(newResponse("BK7")).when(responseSpec).body(codeResponseClass);

        assertThat(client().generateCode(7L)).isEqualTo("BK7");

        verify(bodySpec, never()).header(anyString(), any(String[].class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void generateCode_upstreamUnreachable_returnsNull() {
        when(authProvider.getIfAvailable()).thenReturn(null);
        when(postSpec.uri(anyString())).thenThrow(new ResourceAccessException("connect refused"));

        assertThat(client().generateCode(7L)).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void generateCode_emptyBody_returnsNull() throws Exception {
        when(authProvider.getIfAvailable()).thenReturn(null);
        org.mockito.Mockito.doReturn(null).when(responseSpec).body(codeResponseClass);

        assertThat(client().generateCode(7L)).isNull();
    }

    @Test
    void privateRecords_exposeBeanStyleAccessors() throws Exception {
        Class<?> requestClass = Arrays.stream(ReferralServiceClient.class.getDeclaredClasses())
                .filter(k -> k.getSimpleName().equals("CodeRequest"))
                .findFirst().orElseThrow();
        Constructor<?> ctor = requestClass.getDeclaredConstructor(Long.class);
        ctor.setAccessible(true);
        Object request = ctor.newInstance(3L);
        assertThat(requestClass.getMethod("customerId").invoke(request)).isEqualTo(3L);
        assertThat(requestClass.getMethod("getCustomerId").invoke(request)).isEqualTo(3L);
        assertThat(codeResponseClass.getMethod("getReferralCode").invoke(newResponse("BK9")))
                .isEqualTo("BK9");
    }
}

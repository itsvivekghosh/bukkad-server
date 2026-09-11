package com.bhukkad.identity.service;

import com.bhukkad.common.security.JwtRevocationService;
import com.bhukkad.identity.domain.RefreshToken;
import com.bhukkad.identity.domain.RefreshTokenRepository;
import com.bhukkad.identity.security.JwtProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P1 logout revocation: every logout variant (single-session and all-sessions)
 * and the password-change/reset path ({@code revokeAllForCustomer}) must
 * advance the user's access-token revocation epoch, so validators reject
 * already-issued access tokens immediately instead of for up to the access
 * TTL.
 */
@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceRevocationTest {

    @Mock
    private RefreshTokenRepository repository;
    @Mock
    private JwtProperties jwtProperties;
    @Mock
    private JwtRevocationService revocationService;
    @Mock
    private TransactionTemplate tx;

    @InjectMocks
    private RefreshTokenService service;

    @Test
    void singleSessionLogout_advancesRevocationEpoch() {
        RefreshToken row = RefreshToken.of(7L, "abc", java.time.Instant.now().plusSeconds(600));
        when(repository.findByTokenHash(anyString())).thenReturn(Optional.of(row));
        when(repository.revokeIfLive(anyString(), any())).thenReturn(1);

        service.revoke("presented-raw-token");

        verify(repository).revokeIfLive(anyString(), any());
        verify(revocationService).revokeTokensIssuedBefore(7L);
    }

    @Test
    void allSessionsLogout_advancesRevocationEpoch() {
        when(repository.revokeAllByCustomer(org.mockito.ArgumentMatchers.eq(7L), any())).thenReturn(2);

        service.revokeAllForCustomer(7L);

        verify(revocationService).revokeTokensIssuedBefore(7L);
    }

    @Test
    void unknownRefreshToken_staysNoOp_withoutEpochWrite() {
        when(repository.findByTokenHash(anyString())).thenReturn(Optional.empty());

        service.revoke("unknown-token");

        verify(repository, never()).revokeIfLive(anyString(), any());
        verify(revocationService, never()).revokeTokensIssuedBefore(any(Long.class));
    }

    @Test
    void redisAbsentRevocationService_logoutStillSucceeds() {
        // The platform service is a no-op when Redis is not configured — the
        // logout flow must complete unchanged in minimal contexts.
        @SuppressWarnings("unchecked")
        ObjectProvider<org.springframework.data.redis.core.StringRedisTemplate> absentRedis =
                mock(ObjectProvider.class);
        when(absentRedis.getIfAvailable()).thenReturn(null);
        JwtRevocationService noRedis = new JwtRevocationService(
                absentRedis, mock(ObjectProvider.class), 15);
        RefreshTokenService serviceWithoutRedis =
                new RefreshTokenService(tx, repository, jwtProperties, noRedis);
        when(repository.revokeAllByCustomer(any(Long.class), any())).thenReturn(2);

        assertThat(serviceWithoutRedis.revokeAllForCustomer(7L)).isEqualTo(2);
    }
}

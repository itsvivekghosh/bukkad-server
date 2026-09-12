package com.bhukkad.identity.unit.service;

import com.bhukkad.identity.domain.entity.RefreshToken;
import com.bhukkad.identity.domain.repository.RefreshTokenRepository;
import com.bhukkad.identity.config.JwtProperties;
import com.bhukkad.identity.domain.service.impl.RefreshTokenService;

import com.bhukkad.common.security.JwtRevocationService;
import com.bhukkad.identity.domain.entity.RefreshToken;
import com.bhukkad.identity.domain.repository.RefreshTokenRepository;
import com.bhukkad.identity.config.JwtProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P1 REVOCATION: access-token epochs must be stamped wherever this service
 * kills sessions — per-device logout ({@code revoke}), account-wide kill
 * ({@code revokeAllForCustomer}: full logout, password change/reset, accounts
 * found deactivated at refresh time) — and never on unknown tokens.
 */
@SuppressWarnings("unchecked")
class RefreshTokenServiceRevocationTest {

    private final RefreshTokenRepository repository = mock(RefreshTokenRepository.class);
    private final JwtProperties jwtProperties = mock(JwtProperties.class);
    private final JwtRevocationService revocations = mock(JwtRevocationService.class);
    private final RefreshTokenService service = new RefreshTokenService(
            new TransactionTemplate(), repository, jwtProperties,
            new ObjectProvider<>() {
                @Override
                public JwtRevocationService getObject() {
                    return revocations;
                }

                @Override
                public JwtRevocationService getObject(Object... args) {
                    return revocations;
                }

                @Override
                public JwtRevocationService getIfAvailable() {
                    return revocations;
                }

                @Override
                public JwtRevocationService getIfUnique() {
                    return revocations;
                }
            });

    private static RefreshToken row(long customerId) {
        return RefreshToken.of(customerId, "hash-1", Instant.now().plusSeconds(3600));
    }

    @Test
    void revokeAllForCustomer_stampsAccessEpoch() {
        when(repository.revokeAllByCustomer(eq(7L), any(Instant.class))).thenReturn(2);

        int killed = service.revokeAllForCustomer(7L);

        assertThat(killed).isEqualTo(2);
        verify(revocations).revoke(eq(7L), any(Instant.class));
    }

    @Test
    void singleSessionLogout_stampsEpochOfTheTokenOwner() {
        when(repository.findByTokenHash(any())).thenReturn(Optional.of(row(9L)));

        service.revoke("presented-token");

        verify(repository).revokeIfLive(eq("hash-1"), any(Instant.class));
        verify(revocations).revoke(eq(9L), any(Instant.class));
    }

    @Test
    void unknownToken_isNoOp_neverStampsEpoch() {
        when(repository.findByTokenHash(any())).thenReturn(Optional.empty());

        service.revoke("never-issued");

        verify(revocations, never()).revoke(anyLong(), any(Instant.class));
    }

    @Test
    void blankToken_shortCircuits() {
        service.revoke("  ");

        verify(repository, never()).findByTokenHash(any());
        verify(revocations, never()).revoke(anyLong(), any(Instant.class));
    }

    @Test
    void absentRevocationStore_neverBreaksTheSessionKill() {
        RefreshTokenService noEpochStore = new RefreshTokenService(
                new TransactionTemplate(), repository, jwtProperties,
                new ObjectProvider<>() {
                    @Override
                    public JwtRevocationService getObject() {
                        throw new IllegalStateException("no bean");
                    }

                    @Override
                    public JwtRevocationService getObject(Object... args) {
                        throw new IllegalStateException("no bean");
                    }

                    @Override
                    public JwtRevocationService getIfAvailable() {
                        return null;
                    }

                    @Override
                    public JwtRevocationService getIfUnique() {
                        return null;
                    }
                });
        when(repository.revokeAllByCustomer(eq(3L), any(Instant.class))).thenReturn(1);

        assertThatCode(() -> noEpochStore.revokeAllForCustomer(3L)).doesNotThrowAnyException();
    }
}

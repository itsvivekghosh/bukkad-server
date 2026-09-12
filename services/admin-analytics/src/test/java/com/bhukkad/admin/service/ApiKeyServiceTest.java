package com.bhukkad.admin.service;
import com.bhukkad.admin.domain.service.ApiKeyService;

import com.bhukkad.admin.domain.entity.ApiKey;
import com.bhukkad.admin.domain.repository.ApiKeyRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApiKeyServiceTest {

    @Mock private ApiKeyRepository apiKeyRepository;
    @InjectMocks private ApiKeyService service;

    @Test
    void create_returnsIssuedKeyWithBhkPrefix() {
        when(apiKeyRepository.save(org.mockito.ArgumentMatchers.any(ApiKey.class)))
                .thenAnswer(inv -> {
                    ApiKey key = inv.getArgument(0);
                    key.setId(1L);
                    return key;
                });

        ApiKeyService.IssuedKey issued = service.create("test", 7);

        assertThat(issued.rawKey()).startsWith("bhk-");
        assertThat(issued.id()).isEqualTo(1L);
        assertThat(issued.expiresAt()).isNotNull();
    }

    @Test
    void isValid_activeUnexpiredKey_returnsTrue() {
        ApiKey key = new ApiKey();
        key.setExpiresAt(LocalDateTime.now().plusDays(1));
        when(apiKeyRepository.findByKeyHashAndStatus(anyString(), org.mockito.ArgumentMatchers.eq(ApiKey.STATUS_ACTIVE)))
                .thenReturn(Optional.of(key));

        assertThat(service.isValid("bhk-valid-key")).isTrue();
    }

    @Test
    void isValid_expiredKey_returnsFalse() {
        ApiKey key = new ApiKey();
        key.setExpiresAt(LocalDateTime.now().minusDays(1));
        when(apiKeyRepository.findByKeyHashAndStatus(anyString(), org.mockito.ArgumentMatchers.eq(ApiKey.STATUS_ACTIVE)))
                .thenReturn(Optional.of(key));

        assertThat(service.isValid("bhk-expired-key")).isFalse();
    }

    @Test
    void isValid_unknownKey_returnsFalse() {
        when(apiKeyRepository.findByKeyHashAndStatus(anyString(), org.mockito.ArgumentMatchers.eq(ApiKey.STATUS_ACTIVE)))
                .thenReturn(Optional.empty());

        assertThat(service.isValid("bhk-unknown-key")).isFalse();
    }

    @Test
    void list_returnsBoundedNewestPageAsSafeViews() {
        ApiKey key = new ApiKey();
        key.setId(2L);
        key.setName("ci-bot");
        key.setStatus(ApiKey.STATUS_ACTIVE);
        key.setCreatedAt(LocalDateTime.now().minusDays(1));
        key.setExpiresAt(LocalDateTime.now().plusDays(1));
        when(apiKeyRepository.findAll(org.mockito.ArgumentMatchers.any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(java.util.List.of(key)));

        var views = service.list();

        assertThat(views).hasSize(1);
        assertThat(views.get(0).id()).isEqualTo(2L);
        assertThat(views.get(0).name()).isEqualTo("ci-bot");
        assertThat(views.get(0).status()).isEqualTo(ApiKey.STATUS_ACTIVE);
        assertThat(views.get(0).expiresAt()).isNotNull();
        assertThat(views.get(0)).isEqualTo(views.get(0));
    }
}

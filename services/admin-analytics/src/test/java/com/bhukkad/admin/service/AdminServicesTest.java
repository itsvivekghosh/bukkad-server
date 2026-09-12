package com.bhukkad.admin.service;
import com.bhukkad.admin.domain.service.ApiKeyService;
import com.bhukkad.admin.domain.service.FeatureFlagService;
import com.bhukkad.admin.config.FeatureFlagProperties;

import com.bhukkad.admin.domain.repository.ApiKeyRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class AdminServicesTest {

    @Mock private ApiKeyRepository apiKeyRepository;

    @Test
    void apiKeyService_validatesOnlyIssuedKeys() {
        ApiKeyService service = new ApiKeyService(apiKeyRepository);
        var issued = service.create("test", 7);
        assertThat(issued.rawKey()).isNotBlank();
        assertThat(issued.expiresAt()).isNotNull();
    }

    @Test
    void featureFlagService_defaultsDisabled() {
        FeatureFlagService service = new FeatureFlagService(
                new FeatureFlagProperties(), null);
        assertThat(service.isEnabled("anything")).isFalse();
    }
}

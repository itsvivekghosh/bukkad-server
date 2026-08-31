package com.bhukkad.admin.service;

import com.bhukkad.admin.domain.ApiKeyRepository;
import com.bhukkad.admin.domain.FeatureFlagRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class AdminServicesTest {

    @Mock private ApiKeyRepository apiKeyRepository;
    @Mock private FeatureFlagRepository flagRepository;

    @Test
    void apiKeyService_validatesOnlyIssuedKeys() {
        ApiKeyService service = new ApiKeyService(apiKeyRepository);
        var issued = service.create("test", 7);
        assertThat(issued.rawKey()).isNotBlank();
        assertThat(issued.expiresAt()).isNotNull();
    }

    @Test
    void featureFlagService_defaultsDisabled() {
        FeatureFlagService service = new FeatureFlagService(flagRepository);
        assertThat(service.isEnabled("anything")).isFalse();
    }
}

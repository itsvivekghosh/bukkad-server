package com.bhukkad.admin.service;

import com.bhukkad.admin.domain.FeatureFlag;
import com.bhukkad.admin.domain.FeatureFlagRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FeatureFlagServiceTest {

    @Mock private FeatureFlagRepository flagRepository;
    @InjectMocks private FeatureFlagService service;

    @Test
    void set_existingFlag_updatesEnabled() {
        FeatureFlag existing = new FeatureFlag();
        existing.setFlagName("dark-mode");
        existing.setEnabled(false);
        when(flagRepository.findByFlagName("dark-mode")).thenReturn(Optional.of(existing));
        when(flagRepository.save(any(FeatureFlag.class))).thenAnswer(inv -> inv.getArgument(0));

        FeatureFlag result = service.set("dark-mode", true);

        assertThat(result.getEnabled()).isTrue();
        assertThat(result.getFlagName()).isEqualTo("dark-mode");
        verify(flagRepository).save(existing);
    }

    @Test
    void set_newFlag_createsEnabled() {
        when(flagRepository.findByFlagName("dark-mode")).thenReturn(Optional.empty());
        when(flagRepository.save(any(FeatureFlag.class))).thenAnswer(inv -> inv.getArgument(0));

        FeatureFlag result = service.set("dark-mode", true);

        assertThat(result.getFlagName()).isEqualTo("dark-mode");
        assertThat(result.getEnabled()).isTrue();
    }

    @Test
    void isEnabled_existingFlag_returnsValue() {
        FeatureFlag flag = new FeatureFlag();
        flag.setEnabled(true);
        when(flagRepository.findByFlagName("dark-mode")).thenReturn(Optional.of(flag));

        assertThat(service.isEnabled("dark-mode")).isTrue();
    }

    @Test
    void isEnabled_unknownFlag_returnsFalse() {
        when(flagRepository.findByFlagName("nope")).thenReturn(Optional.empty());

        assertThat(service.isEnabled("nope")).isFalse();
    }
}

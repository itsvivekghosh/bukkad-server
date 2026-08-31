package com.bhukkad.admin.service;

import com.bhukkad.admin.domain.ExperimentExposure;
import com.bhukkad.admin.domain.ExperimentExposureRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExperimentServiceTest {

    @Mock private ExperimentExposureRepository repository;
    @InjectMocks private ExperimentService service;

    @Test
    void assign_newCustomer_assignsDeterministicVariant() {
        when(repository.findByCustomerIdAndExperiment(42L, "checkout-v2"))
                .thenReturn(Optional.empty());
        when(repository.save(any(ExperimentExposure.class))).thenAnswer(inv -> inv.getArgument(0));

        ExperimentExposure exposure =
                service.assign(42L, "checkout-v2", List.of("control", "treatment"));

        assertThat(exposure.getCustomerId()).isEqualTo(42L);
        assertThat(exposure.getExperiment()).isEqualTo("checkout-v2");
        // floorMod(42, 2) = 0 -> control
        assertThat(exposure.getVariant()).isEqualTo("control");
    }

    @Test
    void assign_existingCustomer_returnsCachedExposure() {
        ExperimentExposure existing = new ExperimentExposure();
        existing.setVariant("treatment");
        when(repository.findByCustomerIdAndExperiment(7L, "checkout-v2"))
                .thenReturn(Optional.of(existing));

        ExperimentExposure exposure =
                service.assign(7L, "checkout-v2", List.of("control", "treatment"));

        assertThat(exposure.getVariant()).isEqualTo("treatment");
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never()).save(any(ExperimentExposure.class));
    }

    @Test
    void exposures_delegatesToRepository() {
        when(repository.findByCustomerId(7L)).thenReturn(List.of());

        assertThat(service.exposures(7L)).isEmpty();
        verify(repository).findByCustomerId(7L);
    }
}

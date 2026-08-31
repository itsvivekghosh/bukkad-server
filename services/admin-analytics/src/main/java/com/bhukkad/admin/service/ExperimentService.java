package com.bhukkad.admin.service;

import com.bhukkad.admin.domain.ExperimentExposure;
import com.bhukkad.admin.domain.ExperimentExposureRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * A/B experiment assignment (Batch E depth): deterministic variant assignment,
 * idempotent per (customer, experiment).
 */
@Service
@RequiredArgsConstructor
public class ExperimentService {

    private final ExperimentExposureRepository repository;

    @Transactional
    public ExperimentExposure assign(Long customerId, String experiment, List<String> variants) {
        return repository.findByCustomerIdAndExperiment(customerId, experiment)
                .orElseGet(() -> {
                    String variant = variants.get(Math.floorMod(customerId, variants.size()));
                    ExperimentExposure exposure = new ExperimentExposure();
                    exposure.setCustomerId(customerId);
                    exposure.setExperiment(experiment);
                    exposure.setVariant(variant);
                    return repository.save(exposure);
                });
    }

    @Transactional(readOnly = true)
    public List<ExperimentExposure> exposures(Long customerId) {
        return repository.findByCustomerId(customerId);
    }
}
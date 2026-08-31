package com.bhukkad.admin.service;

import com.bhukkad.admin.domain.FeatureFlag;
import com.bhukkad.admin.domain.FeatureFlagRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Feature-flag registry (port of monolith {@code FeatureFlagService}).
 */
@Service
@RequiredArgsConstructor
public class FeatureFlagService {

    private final FeatureFlagRepository flagRepository;

    @Transactional
    public FeatureFlag set(String flagName, boolean enabled) {
        return flagRepository.findByFlagName(flagName)
                .map(existing -> {
                    existing.setEnabled(enabled);
                    return flagRepository.save(existing);
                })
                .orElseGet(() -> {
                    FeatureFlag flag = new FeatureFlag();
                    flag.setFlagName(flagName);
                    flag.setEnabled(enabled);
                    return flagRepository.save(flag);
                });
    }

    @Transactional(readOnly = true)
    public boolean isEnabled(String flagName) {
        return flagRepository.findByFlagName(flagName)
                .map(FeatureFlag::getEnabled)
                .orElse(false);
    }
}
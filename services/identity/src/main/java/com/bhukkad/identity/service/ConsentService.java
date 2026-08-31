package com.bhukkad.identity.service;

import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.identity.domain.ConsentRecord;
import com.bhukkad.identity.domain.ConsentRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * GDPR/privacy consent management (Priority 1).
 */
@Service
@RequiredArgsConstructor
public class ConsentService {

    private final ConsentRecordRepository consentRepository;

    @Transactional
    public ConsentRecord record(Long userId, String purpose, boolean granted) {
        return consentRepository.findByUserIdAndPurpose(userId, purpose)
                .map(existing -> {
                    existing.setGranted(granted);
                    return consentRepository.save(existing);
                })
                .orElseGet(() -> {
                    ConsentRecord record = new ConsentRecord();
                    record.setUserId(userId);
                    record.setPurpose(purpose);
                    record.setGranted(granted);
                    return consentRepository.save(record);
                });
    }

    @Transactional(readOnly = true)
    public boolean hasConsent(Long userId, String purpose) {
        return consentRepository.findByUserIdAndPurpose(userId, purpose)
                .map(ConsentRecord::getGranted)
                .orElse(false);
    }

    @Transactional
    public void withdraw(Long userId, String purpose) {
        ConsentRecord record = consentRepository.findByUserIdAndPurpose(userId, purpose)
                .orElseThrow(() -> new ResourceNotFoundException("No consent for purpose: " + purpose));
        record.setGranted(false);
        consentRepository.save(record);
    }
}
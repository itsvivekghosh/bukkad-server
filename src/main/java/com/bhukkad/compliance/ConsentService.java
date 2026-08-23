package com.bhukkad.compliance;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConsentService {

    private final ConsentRecordRepository consentRecordRepository;

    @Transactional(readOnly = true)
    public List<ConsentRecord> getConsents(Long userId) {
        return consentRecordRepository.findByUserId(userId);
    }

    @Transactional
    public ConsentRecord setConsent(Long userId, String purpose, Boolean granted, String source) {
        ConsentRecord record = consentRecordRepository.findByUserIdAndPurpose(userId, purpose)
                .orElseGet(() -> {
                    ConsentRecord created = new ConsentRecord();
                    created.setUserId(userId);
                    created.setPurpose(purpose);
                    return created;
                });
        record.setGranted(granted);
        if (source != null) {
            record.setSource(source);
        }
        ConsentRecord saved = consentRecordRepository.save(record);
        log.info("Consent recorded | userId={} | purpose={} | granted={} | source={}",
                userId, purpose, granted, source);
        return saved;
    }

    @Transactional(readOnly = true)
    public boolean allConsented(Long userId, String purpose) {
        return consentRecordRepository.findByUserIdAndPurpose(userId, purpose)
                .map(ConsentRecord::getGranted)
                .filter(Boolean.TRUE::equals)
                .orElse(false);
    }

    /**
     * Revokes every consent purpose for the user. Used by the data-deletion flow:
     * after anonymization no marketing or notification contact is permitted.
     */
    @Transactional
    public void revokeAllConsents(Long userId) {
        for (ConsentRecord record : consentRecordRepository.findByUserId(userId)) {
            if (Boolean.TRUE.equals(record.getGranted())) {
                record.setGranted(false);
                record.setSource("erasure");
                consentRecordRepository.save(record);
            }
        }
    }
}

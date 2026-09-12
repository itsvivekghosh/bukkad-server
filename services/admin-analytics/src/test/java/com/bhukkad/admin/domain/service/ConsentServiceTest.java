package com.bhukkad.admin.domain.service;

import com.bhukkad.admin.domain.entity.ConsentRecord;
import com.bhukkad.admin.domain.repository.ConsentRecordRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConsentServiceTest {

    @Mock private ConsentRecordRepository consentRecordRepository;
    @InjectMocks private ConsentService service;

    private ConsentRecord existing(Long userId, String purpose, Boolean granted) {
        ConsentRecord record = new ConsentRecord();
        record.setUserId(userId);
        record.setPurpose(purpose);
        record.setGranted(granted);
        return record;
    }

    @Test
    void getConsents_returnsRepositoryRows() {
        when(consentRecordRepository.findByUserId(7L)).thenReturn(List.of(existing(7L, "marketing", true)));

        assertThat(service.getConsents(7L)).hasSize(1);
    }

    @Test
    void setConsent_createsNewRecordWhenAbsent() {
        when(consentRecordRepository.findByUserIdAndPurpose(7L, "marketing"))
                .thenReturn(Optional.empty());
        when(consentRecordRepository.save(any(ConsentRecord.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ConsentRecord saved = service.setConsent(7L, "marketing", true, "checkout");

        assertThat(saved.getUserId()).isEqualTo(7L);
        assertThat(saved.getPurpose()).isEqualTo("marketing");
        assertThat(saved.getGranted()).isTrue();
        assertThat(saved.getSource()).isEqualTo("checkout");
    }

    @Test
    void setConsent_updatesExistingAndKeepsSourceWhenNull() {
        ConsentRecord record = existing(7L, "marketing", false);
        record.setSource("onboarding");
        when(consentRecordRepository.findByUserIdAndPurpose(7L, "marketing")).thenReturn(Optional.of(record));
        when(consentRecordRepository.save(any(ConsentRecord.class))).thenAnswer(inv -> inv.getArgument(0));

        ConsentRecord saved = service.setConsent(7L, "marketing", true, null);

        assertThat(saved.getGranted()).isTrue();
        assertThat(saved.getSource()).isEqualTo("onboarding");
    }

    @Test
    void allConsents_grantedtrueFalseAbsent() {
        when(consentRecordRepository.findByUserIdAndPurpose(7L, "marketing"))
                .thenReturn(Optional.of(existing(7L, "marketing", true)));
        assertThat(service.allConsented(7L, "marketing")).isTrue();

        when(consentRecordRepository.findByUserIdAndPurpose(7L, "marketing"))
                .thenReturn(Optional.of(existing(7L, "marketing", false)));
        assertThat(service.allConsented(7L, "marketing")).isFalse();

        when(consentRecordRepository.findByUserIdAndPurpose(7L, "marketing"))
                .thenReturn(Optional.empty());
        assertThat(service.allConsented(7L, "marketing")).isFalse();
    }

    @Test
    void revokeAllConsents_onlyTouchesGrantedRecords() {
        ConsentRecord granted = existing(7L, "marketing", true);
        ConsentRecord alreadyRevoked = existing(7L, "notifications", false);
        ConsentRecord nullGranted = existing(7L, "sms", null);
        when(consentRecordRepository.findByUserId(7L))
                .thenReturn(List.of(granted, alreadyRevoked, nullGranted));
        when(consentRecordRepository.save(any(ConsentRecord.class))).thenAnswer(inv -> inv.getArgument(0));

        service.revokeAllConsents(7L);

        ArgumentCaptor<ConsentRecord> captor = ArgumentCaptor.forClass(ConsentRecord.class);
        verify(consentRecordRepository).save(captor.capture());
        assertThat(captor.getValue().getPurpose()).isEqualTo("marketing");
        assertThat(captor.getValue().getGranted()).isFalse();
        assertThat(captor.getValue().getSource()).isEqualTo("erasure");
        assertThat(alreadyRevoked.getGranted()).isFalse();
        assertThat(nullGranted.getGranted()).isNull();
    }
}

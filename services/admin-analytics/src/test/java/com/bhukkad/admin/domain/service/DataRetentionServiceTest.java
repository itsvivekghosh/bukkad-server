package com.bhukkad.admin.domain.service;

import com.bhukkad.admin.config.ComplianceProperties;
import com.bhukkad.admin.domain.repository.DataExportRequestRepository;
import com.bhukkad.admin.domain.repository.FraudEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DataRetentionServiceTest {

    @Mock private ComplianceProperties complianceProperties;
    @Mock private DataExportRequestRepository dataExportRequestRepository;
    @Mock private FraudEventRepository fraudEventRepository;
    @Mock private TransactionTemplate transactionTemplate;

    private DataRetentionService service;

    @BeforeEach
    void setUp() {
        service = new DataRetentionService(complianceProperties, dataExportRequestRepository,
                fraudEventRepository, transactionTemplate);
        org.mockito.Mockito.lenient()
                .when(transactionTemplate.execute(any()))
                .thenAnswer(inv -> {
                    TransactionCallback<?> cb = inv.getArgument(0);
                    // Guard: Mockito's when() re-invokes with a null matcher arg.
                    return cb == null ? null
                            : cb.doInTransaction(org.mockito.Mockito.mock(TransactionStatus.class));
                });
    }

    @Test
    void purge_disabledCompliance_doesNothing() {
        when(complianceProperties.isEnabled()).thenReturn(false);

        service.purgeExpiredData();

        verify(dataExportRequestRepository, never()).deleteByCreatedAtBefore(any());
        verify(fraudEventRepository, never()).deleteByCreatedAtBefore(any());
    }

    @Test
    void purge_removesExpiredArtifactsInSeparateTransactions() {
        when(complianceProperties.isEnabled()).thenReturn(true);
        when(complianceProperties.getRetentionDays()).thenReturn(90);
        when(dataExportRequestRepository.deleteByCreatedAtBefore(any(LocalDateTime.class))).thenReturn(3L);
        when(fraudEventRepository.deleteByCreatedAtBefore(any(LocalDateTime.class))).thenReturn(5L);

        service.purgeExpiredData();

        verify(dataExportRequestRepository).deleteByCreatedAtBefore(any(LocalDateTime.class));
        verify(fraudEventRepository).deleteByCreatedAtBefore(any(LocalDateTime.class));
        verify(transactionTemplate, org.mockito.Mockito.times(2)).execute(any());
    }

    @Test
    void purge_failingTransactionTemplateIsSwallowedSoSweepContinues() {
        when(complianceProperties.isEnabled()).thenReturn(true);
        when(complianceProperties.getRetentionDays()).thenReturn(90);
        // export purge blows up inside its tx; fraud purge must still run.
        when(transactionTemplate.execute(any()))
                .thenThrow(new RuntimeException("db down"))
                .thenAnswer(inv -> ((TransactionCallback<?>) inv.getArgument(0))
                        .doInTransaction(org.mockito.Mockito.mock(TransactionStatus.class)));
        when(fraudEventRepository.deleteByCreatedAtBefore(any(LocalDateTime.class))).thenReturn(1L);

        service.purgeExpiredData();

        verify(fraudEventRepository).deleteByCreatedAtBefore(any(LocalDateTime.class));
    }

    @Test
    void purge_nullTransactionResultIsTreatedAsZero() {
        when(complianceProperties.isEnabled()).thenReturn(true);
        when(complianceProperties.getRetentionDays()).thenReturn(90);
        when(transactionTemplate.execute(any())).thenReturn(null);

        service.purgeExpiredData(); // no NPE, logs nothing interesting
    }
}

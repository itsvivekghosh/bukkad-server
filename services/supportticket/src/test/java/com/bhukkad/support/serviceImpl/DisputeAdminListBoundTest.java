package com.bhukkad.support.serviceImpl;

import com.bhukkad.support.client.OrderServiceClient;
import com.bhukkad.support.dto.response.DisputeResponse;
import com.bhukkad.support.entity.Dispute;
import com.bhukkad.support.repository.DisputeRepository;
import com.bhukkad.support.wallet.WalletCreditClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PERF-3 §2.1: the admin dispute history listed EVERY dispute and sorted in
 * the JVM. It is now a bounded, newest-first SQL page (ORDER BY created_at
 * DESC + limit); the List response shape is preserved (additively capped at
 * the 200 newest rows — deeper history needs a paging contract change).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DisputeAdminListBoundTest {

    @Mock private DisputeRepository disputeRepository;
    @Mock private WalletCreditClient walletClient;
    @Mock private OrderServiceClient orderClient;

    private DisputeResolutionServiceImpl service() {
        DisputeResolutionServiceImpl s =
                new DisputeResolutionServiceImpl(disputeRepository, walletClient, orderClient);
        ReflectionTestUtils.setField(s, "lateThresholdMinutes", 30L);
        return s;
    }

    @Test
    void listForAdmin_queriesBoundedNewestFirstPage() {
        Dispute d = new Dispute();
        d.setId(1L);
        d.setCreatedAt(LocalDateTime.now());
        d.setStatus(Dispute.DisputeStatus.OPEN);
        d.setType(Dispute.DisputeType.FOOD_QUALITY);
        when(disputeRepository.findAllByOrderByCreatedAtDesc(any(Pageable.class)))
                .thenReturn(List.of(d));

        List<DisputeResponse> page = service().listForAdmin();

        assertThat(page).hasSize(1);
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(disputeRepository).findAllByOrderByCreatedAtDesc(captor.capture());
        // ORDER BY created_at DESC comes from the derived repository method
        // name; the pageable contributes the bounded page only.
        assertThat(captor.getValue().getPageSize()).isEqualTo(200);
        assertThat(captor.getValue().getPageNumber()).isZero();
        verify(disputeRepository, never()).findAll();
    }
}

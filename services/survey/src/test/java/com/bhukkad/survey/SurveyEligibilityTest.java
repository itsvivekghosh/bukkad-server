package com.bhukkad.survey;

import com.bhukkad.survey.infrastructure.client.OrderOwnershipClient;
import com.bhukkad.survey.domain.entity.DeliverySurvey;
import com.bhukkad.survey.domain.repository.DeliverySurveyRepository;
import com.bhukkad.survey.domain.service.impl.SurveyServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SV-1 (audit): a survey may only be filed by the order's owner — the
 * endpoint previously trusted a client-supplied orderId.
 */
@ExtendWith(MockitoExtension.class)
class SurveyEligibilityTest {

    @Mock private DeliverySurveyRepository surveyRepository;
    @Mock private OrderOwnershipClient ownershipClient;
    @InjectMocks private SurveyServiceImpl service;

    @Test
    void nonOwner_deniedAndNothingPersisted() {
        when(ownershipClient.ownsOrder(7L, 100L)).thenReturn(false);

        assertThatThrownBy(() -> service.submitSurvey(
                7L, 100L, 5L, 5, 4, 5, "great"))
                .isInstanceOf(AccessDeniedException.class);
        verify(surveyRepository, never()).save(any());
        verify(surveyRepository, never()).findByOrderId(any());
    }

    @Test
    void owner_persists() {
        when(ownershipClient.ownsOrder(7L, 100L)).thenReturn(true);
        when(surveyRepository.findByOrderId(100L)).thenReturn(Optional.empty());
        when(surveyRepository.save(any(DeliverySurvey.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        DeliverySurvey saved = service.submitSurvey(7L, 100L, 5L, 5, 4, 5, "  good  ");
        assertThat(saved.getCustomerId()).isEqualTo(7L);
        assertThat(saved.getComment()).isEqualTo("good");
    }

    @Test
    void missingOwnerCheckStillRequiresAuthenticatedCustomer() {
        assertThatThrownBy(() -> service.submitSurvey(
                null, 100L, 5L, 5, 4, 5, null))
                .isInstanceOf(com.bhukkad.common.error.UnauthorizedException.class);
    }
}

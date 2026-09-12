package com.bhukkad.growth.api.controller;

import com.bhukkad.common.error.UnauthorizedException;
import com.bhukkad.common.security.TokenPrincipal;
import com.bhukkad.growth.api.dto.response.LoyaltyPointsResponse;
import com.bhukkad.growth.domain.service.LoyaltyService;
import com.bhukkad.growth.api.GrowthExceptionHandler;
import com.bhukkad.growth.api.LoyaltyDailyCapExceededException;
import com.bhukkad.common.error.ApiError;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Self-scoped loyalty surface + the growth-specific exception mapping. */
@ExtendWith(MockitoExtension.class)
class LoyaltySelfAndErrorSurfaceTest {

    @Mock private LoyaltyService loyaltyService;

    @InjectMocks private LoyaltySelfController selfController;

    @Test
    void loyaltyPoints_nullPrincipal_throwsUnauthorized() {
        assertThatThrownBy(() -> selfController.loyaltyPoints(null))
                .isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> selfController.loyaltyPoints(new TokenPrincipal(null, "a@b.c", "CUSTOMER")))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void loyaltyPoints_authenticated_readsSubjectOnly() {
        LoyaltyPointsResponse body = LoyaltyPointsResponse.builder().customerId(4L).build();
        when(loyaltyService.getLoyaltyPoints(4L)).thenReturn(body);

        var response = selfController.loyaltyPoints(
                new TokenPrincipal(4L, "a@b.c", "CUSTOMER"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(body);
    }

    @Test
    void dailyCap_mapsTo422WithStableCode() {
        ResponseEntity<ApiError> response = new GrowthExceptionHandler()
                .dailyCap(new LoyaltyDailyCapExceededException("cap hit"));

        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(response.getBody().code()).isEqualTo("DAILY_CREDIT_CAP_EXCEEDED");
        assertThat(response.getBody().message()).isEqualTo("cap hit");
        assertThat(response.getBody().status()).isEqualTo(422);
    }
}

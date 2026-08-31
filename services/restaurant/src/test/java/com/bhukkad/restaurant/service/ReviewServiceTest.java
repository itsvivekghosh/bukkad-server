package com.bhukkad.restaurant.service;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.restaurant.domain.Review;
import com.bhukkad.restaurant.domain.ReviewRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReviewServiceTest {

    @Mock private ReviewRepository reviewRepository;
    @InjectMocks private ReviewService service;

    @Test
    void submit_validRating_savesAsPending() {
        when(reviewRepository.save(any(Review.class))).thenAnswer(inv -> inv.getArgument(0));

        Review review = service.submit(1L, 2L, 4, "Great");

        assertThat(review.getStatus()).isEqualTo(Review.STATUS_PENDING);
        assertThat(review.getRating()).isEqualTo(4);
    }

    @Test
    void submit_ratingOutOfRange_throws() {
        assertThatThrownBy(() -> service.submit(1L, 2L, 6, "x"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("between 1 and 5");
        assertThatThrownBy(() -> service.submit(1L, 2L, 0, "x"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void moderate_invalidStatus_throws() {
        assertThatThrownBy(() -> service.moderate(1L, "BOGUS"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void inventoryAlert_outOfStock_whenZero() {
        com.bhukkad.restaurant.domain.InventoryAlertRepository repo =
                org.mockito.Mockito.mock(com.bhukkad.restaurant.domain.InventoryAlertRepository.class);
        InventoryAlertService alertService = new InventoryAlertService(repo);
        when(repo.save(any(com.bhukkad.restaurant.domain.InventoryAlert.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var alert = alertService.raise(1L, 0, 5);

        assertThat(alert.getAlertType()).isEqualTo(com.bhukkad.restaurant.domain.InventoryAlert.TYPE_OUT_OF_STOCK);
    }

    @Test
    void inventoryAlert_lowStock_whenPositive() {
        com.bhukkad.restaurant.domain.InventoryAlertRepository repo =
                org.mockito.Mockito.mock(com.bhukkad.restaurant.domain.InventoryAlertRepository.class);
        InventoryAlertService alertService = new InventoryAlertService(repo);
        when(repo.save(any(com.bhukkad.restaurant.domain.InventoryAlert.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var alert = alertService.raise(1L, 3, 5);

        assertThat(alert.getAlertType()).isEqualTo(com.bhukkad.restaurant.domain.InventoryAlert.TYPE_LOW_STOCK);
    }
}

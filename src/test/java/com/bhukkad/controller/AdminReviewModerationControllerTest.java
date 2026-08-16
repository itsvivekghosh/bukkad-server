package com.bhukkad.controller;

import com.bhukkad.dto.response.ApiResponse;
import com.bhukkad.entity.Review;
import com.bhukkad.exception.BusinessException;
import com.bhukkad.service.ReviewService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminReviewModerationControllerTest {

    @Mock
    private ReviewService reviewService;

    @InjectMocks
    private AdminReviewModerationController controller;

    @Test
    void getModerationQueue_defaultsToPendingWhenStatusOmitted() {
        Review review = new Review();
        when(reviewService.getModerationQueue(null)).thenReturn(List.of(review));

        ResponseEntity<ApiResponse<List<Review>>> response = controller.getModerationQueue(null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(List.of(review), response.getBody().getData());
        verify(reviewService).getModerationQueue(null);
    }

    @Test
    void getModerationQueue_parsesStatusFilter() {
        when(reviewService.getModerationQueue(Review.ModerationStatus.REJECTED))
                .thenReturn(List.of());

        ResponseEntity<ApiResponse<List<Review>>> response =
                controller.getModerationQueue("rejected");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(reviewService).getModerationQueue(Review.ModerationStatus.REJECTED);
    }

    @Test
    void moderateReview_approvesAndReturnsUpdatedReview() {
        Review review = new Review();
        review.setModerationStatus(Review.ModerationStatus.APPROVED);
        when(reviewService.moderateReview(9L, Review.ModerationStatus.APPROVED)).thenReturn(review);

        ResponseEntity<ApiResponse<Review>> response =
                controller.moderateReview(9L, "approved");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Review moderation updated", response.getBody().getMessage());
        assertEquals(review, response.getBody().getData());
    }

    @Test
    void moderateReview_rejectsInvalidStatus() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.moderateReview(9L, "spam"));

        assertTrue(ex.getMessage().contains("Invalid moderation status"));
        verifyNoInteractions(reviewService);
    }
}

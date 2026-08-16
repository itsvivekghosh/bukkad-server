package com.bhukkad.controller;

import com.bhukkad.dto.request.ReviewRequest;
import com.bhukkad.dto.response.ApiResponse;
import com.bhukkad.entity.Review;
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
import org.junit.jupiter.api.Tag;

@Tag("regression")
@ExtendWith(MockitoExtension.class)
public class ReviewControllerTest {

    @Mock
    private ReviewService reviewService;

    @InjectMocks
    private ReviewController reviewController;

    @Test
    void createReview_returnsSubmittedReview() {
        ReviewRequest request = new ReviewRequest();
        Review review = new Review();
        when(reviewService.createReview(request)).thenReturn(review);

        ResponseEntity<ApiResponse<Review>> response = reviewController.createReview(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Review submitted successfully", response.getBody().getMessage());
        assertEquals(review, response.getBody().getData());
    }

    @Test
    void getRestaurantReviews_returnsList() {
        List<Review> reviews = List.of(new Review());
        when(reviewService.getRestaurantReviews(1L)).thenReturn(reviews);

        ResponseEntity<ApiResponse<List<Review>>> response = reviewController.getRestaurantReviews(1L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(reviews, response.getBody().getData());
    }

    @Test
    void getMyReviews_returnsCustomerReviews() {
        List<Review> reviews = List.of(new Review());
        when(reviewService.getCustomerReviews()).thenReturn(reviews);

        ResponseEntity<ApiResponse<List<Review>>> response = reviewController.getMyReviews();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(reviews, response.getBody().getData());
    }

    @Test
    void getReviewByOrderId_returnsReview() {
        Review review = new Review();
        when(reviewService.getReviewByOrderId(11L)).thenReturn(review);

        ResponseEntity<ApiResponse<Review>> response = reviewController.getReviewByOrderId(11L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(review, response.getBody().getData());
    }

    @Test
    void deleteReview_returnsSuccess() {
        ResponseEntity<ApiResponse<Void>> response = reviewController.deleteReview(4L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Review deleted successfully", response.getBody().getMessage());
        verify(reviewService).deleteReview(4L);
    }
}

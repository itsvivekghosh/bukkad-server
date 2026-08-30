package com.bhukkad.controller;

import com.bhukkad.dto.request.MenuItemRatingRequest;
import com.bhukkad.dto.request.ReviewRequest;
import com.bhukkad.dto.response.ApiResponse;
import com.bhukkad.dto.response.MenuItemRatingResponse;
import com.bhukkad.dto.response.ReviewResponse;
import com.bhukkad.service.MenuItemRatingService;
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

    @Mock
    private MenuItemRatingService menuItemRatingService;

    @InjectMocks
    private ReviewController reviewController;

    @Test
    void createReview_returnsSubmittedReview() {
        ReviewRequest request = new ReviewRequest();
        ReviewResponse review = new ReviewResponse();
        when(reviewService.createReview(request)).thenReturn(review);

        ResponseEntity<ApiResponse<ReviewResponse>> response = reviewController.createReview(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Review submitted successfully", response.getBody().getMessage());
        assertEquals(review, response.getBody().getData());
    }

    @Test
    void getRestaurantReviews_returnsList() {
        List<ReviewResponse> reviews = List.of(new ReviewResponse());
        when(reviewService.getRestaurantReviews(1L)).thenReturn(reviews);

        ResponseEntity<ApiResponse<List<ReviewResponse>>> response = reviewController.getRestaurantReviews(1L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(reviews, response.getBody().getData());
    }

    @Test
    void getMyReviews_returnsCustomerReviews() {
        List<ReviewResponse> reviews = List.of(new ReviewResponse());
        when(reviewService.getCustomerReviews()).thenReturn(reviews);

        ResponseEntity<ApiResponse<List<ReviewResponse>>> response = reviewController.getMyReviews();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(reviews, response.getBody().getData());
    }

    @Test
    void getReviewByOrderId_returnsReview() {
        ReviewResponse review = new ReviewResponse();
        when(reviewService.getReviewByOrderId(11L)).thenReturn(review);

        ResponseEntity<ApiResponse<ReviewResponse>> response = reviewController.getReviewByOrderId(11L);

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

    @Test
    void rateMenuItem_returnsRating() {
        MenuItemRatingRequest request = new MenuItemRatingRequest();
        MenuItemRatingResponse rating = MenuItemRatingResponse.builder().build();
        when(menuItemRatingService.rateMenuItem(request)).thenReturn(rating);

        ResponseEntity<ApiResponse<MenuItemRatingResponse>> response = reviewController.rateMenuItem(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Menu item rated successfully", response.getBody().getMessage());
        assertEquals(rating, response.getBody().getData());
    }

    @Test
    void getMenuItemRatings_returnsRatings() {
        List<MenuItemRatingResponse> ratings = List.of(MenuItemRatingResponse.builder().build());
        when(menuItemRatingService.getMenuItemRatings(6L)).thenReturn(ratings);

        ResponseEntity<ApiResponse<List<MenuItemRatingResponse>>> response =
                reviewController.getMenuItemRatings(6L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(ratings, response.getBody().getData());
    }
}

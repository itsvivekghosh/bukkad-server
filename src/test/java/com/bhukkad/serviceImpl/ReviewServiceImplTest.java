package com.bhukkad.serviceImpl;

import com.bhukkad.dto.request.ReviewRequest;
import com.bhukkad.entity.Customer;
import com.bhukkad.entity.Order;
import com.bhukkad.entity.Restaurant;
import com.bhukkad.entity.Review;
import com.bhukkad.exception.BusinessException;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.repository.CustomerRepository;
import com.bhukkad.repository.OrderRepository;
import com.bhukkad.repository.RestaurantRepository;
import com.bhukkad.repository.ReviewRepository;
import com.bhukkad.security.SecurityUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReviewServiceImplTest {

    @Mock
    private ReviewRepository reviewRepository;
    @Mock
    private OrderRepository orderRepository;
    @Mock
    private CustomerRepository customerRepository;
    @Mock
    private RestaurantRepository restaurantRepository;
    @Mock
    private SecurityUtils securityUtils;

    @InjectMocks
    private ReviewServiceImpl reviewService;

    // ==================== createReview ====================

    @Test
    void createReview_customerNotFound_throws() {
        when(securityUtils.getCurrentUserId()).thenReturn(1L);
        when(customerRepository.findById(1L)).thenReturn(Optional.empty());

        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                () -> reviewService.createReview(reviewRequest(10L)));
        assertEquals("Customer not found", ex.getMessage());
    }

    @Test
    void createReview_orderNotFound_throws() {
        when(securityUtils.getCurrentUserId()).thenReturn(1L);
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer(1L)));
        when(orderRepository.findByIdWithDetails(10L)).thenReturn(Optional.empty());

        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                () -> reviewService.createReview(reviewRequest(10L)));
        assertEquals("Order not found", ex.getMessage());
    }

    @Test
    void createReview_orderBelongsToAnotherCustomer_throwsBusinessException() {
        when(securityUtils.getCurrentUserId()).thenReturn(1L);
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer(1L)));
        Order order = order(10L, customer(2L), Order.OrderStatus.DELIVERED);
        when(orderRepository.findByIdWithDetails(10L)).thenReturn(Optional.of(order));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> reviewService.createReview(reviewRequest(10L)));
        assertEquals("You can only review your own orders", ex.getMessage());
    }

    @Test
    void createReview_orderNotDelivered_throwsBusinessException() {
        when(securityUtils.getCurrentUserId()).thenReturn(1L);
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer(1L)));
        Order order = order(10L, customer(1L), Order.OrderStatus.CONFIRMED);
        when(orderRepository.findByIdWithDetails(10L)).thenReturn(Optional.of(order));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> reviewService.createReview(reviewRequest(10L)));
        assertEquals("You can only review delivered orders", ex.getMessage());
    }

    @Test
    void createReview_reviewAlreadyExists_throwsBusinessException() {
        when(securityUtils.getCurrentUserId()).thenReturn(1L);
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer(1L)));
        Order order = order(10L, customer(1L), Order.OrderStatus.DELIVERED);
        when(orderRepository.findByIdWithDetails(10L)).thenReturn(Optional.of(order));
        when(reviewRepository.findByOrderId(10L)).thenReturn(Optional.of(new Review()));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> reviewService.createReview(reviewRequest(10L)));
        assertEquals("Review already exists for this order", ex.getMessage());
    }

    @Test
    void createReview_success_savesReviewAndUpdatesRestaurantRating() {
        Customer customer = customer(1L);
        Restaurant restaurant = restaurant(30L);
        Order order = order(10L, customer, Order.OrderStatus.DELIVERED);
        order.setRestaurant(restaurant);
        when(securityUtils.getCurrentUserId()).thenReturn(1L);
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer));
        when(orderRepository.findByIdWithDetails(10L)).thenReturn(Optional.of(order));
        when(reviewRepository.findByOrderId(10L)).thenReturn(Optional.empty());
        when(reviewRepository.save(any(Review.class))).thenAnswer(inv -> {
            Review saved = inv.getArgument(0);
            saved.setId(77L);
            return saved;
        });
        when(reviewRepository.getAverageRatingByRestaurant(30L)).thenReturn(4.5);
        when(reviewRepository.countByRestaurant(30L)).thenReturn(8L);

        ReviewRequest request = reviewRequest(10L);
        Review result = reviewService.createReview(request);

        assertEquals(77L, result.getId());
        assertEquals(customer, result.getCustomer());
        assertEquals(restaurant, result.getRestaurant());
        assertEquals(order, result.getOrder());
        assertEquals(5, result.getRating());
        assertEquals("Great food", result.getComment());
        assertEquals(4, result.getFoodRating());
        assertEquals(5, result.getDeliveryRating());
        assertEquals(List.of("img1.jpg"), result.getImages());
        assertEquals(4.5, restaurant.getAverageRating());
        assertEquals(8, restaurant.getTotalReviews());
        verify(reviewRepository).save(any(Review.class));
        verify(restaurantRepository).save(restaurant);
    }

    @Test
    void createReview_nullAverageRating_setsZeroOnRestaurantAndSavesIt() {
        Customer customer = customer(1L);
        Restaurant restaurant = restaurant(30L);
        restaurant.setAverageRating(3.0);
        restaurant.setTotalReviews(2);
        Order order = order(10L, customer, Order.OrderStatus.DELIVERED);
        order.setRestaurant(restaurant);
        when(securityUtils.getCurrentUserId()).thenReturn(1L);
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer));
        when(orderRepository.findByIdWithDetails(10L)).thenReturn(Optional.of(order));
        when(reviewRepository.findByOrderId(10L)).thenReturn(Optional.empty());
        when(reviewRepository.save(any(Review.class))).thenAnswer(inv -> inv.getArgument(0));
        when(reviewRepository.getAverageRatingByRestaurant(30L)).thenReturn(null);
        when(reviewRepository.countByRestaurant(30L)).thenReturn(0L);

        reviewService.createReview(reviewRequest(10L));

        assertEquals(0.0, restaurant.getAverageRating());
        assertEquals(0, restaurant.getTotalReviews());
        verify(restaurantRepository).save(restaurant);
    }

    // ==================== reads ====================

    @Test
    void getRestaurantReviews_delegatesToRepository() {
        List<Review> reviews = List.of(new Review());
        when(reviewRepository.findByRestaurantIdWithDetails(30L)).thenReturn(reviews);

        assertSame(reviews, reviewService.getRestaurantReviews(30L));
    }

    @Test
    void getCustomerReviews_usesCurrentUserId() {
        List<Review> reviews = List.of(new Review());
        when(securityUtils.getCurrentUserId()).thenReturn(1L);
        when(reviewRepository.findByCustomerIdWithDetails(1L)).thenReturn(reviews);

        assertSame(reviews, reviewService.getCustomerReviews());
    }

    @Test
    void getReviewByOrderId_found() {
        Review review = new Review();
        review.setId(4L);
        when(reviewRepository.findByOrderIdWithDetails(10L)).thenReturn(Optional.of(review));

        assertSame(review, reviewService.getReviewByOrderId(10L));
    }

    @Test
    void getReviewByOrderId_notFound_throws() {
        when(reviewRepository.findByOrderIdWithDetails(10L)).thenReturn(Optional.empty());

        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                () -> reviewService.getReviewByOrderId(10L));
        assertEquals("Review not found", ex.getMessage());
    }

    // ==================== deleteReview ====================

    @Test
    void deleteReview_notFound_throws() {
        when(reviewRepository.findByIdWithDetails(4L)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> reviewService.deleteReview(4L));
    }

    @Test
    void deleteReview_notOwner_throwsBusinessException() {
        Review review = new Review();
        review.setCustomer(customer(2L));
        when(reviewRepository.findByIdWithDetails(4L)).thenReturn(Optional.of(review));
        when(securityUtils.getCurrentUserId()).thenReturn(1L);

        BusinessException ex = assertThrows(BusinessException.class, () -> reviewService.deleteReview(4L));
        assertEquals("You can only delete your own reviews", ex.getMessage());
        verify(reviewRepository, never()).delete(any());
    }

    @Test
    void deleteReview_owner_deletesAndRecalculatesRatingAndSavesRestaurant() {
        Restaurant restaurant = restaurant(30L);
        Review review = new Review();
        review.setId(4L);
        review.setCustomer(customer(1L));
        review.setRestaurant(restaurant);
        when(reviewRepository.findByIdWithDetails(4L)).thenReturn(Optional.of(review));
        when(securityUtils.getCurrentUserId()).thenReturn(1L);
        when(reviewRepository.getAverageRatingByRestaurant(30L)).thenReturn(3.2);
        when(reviewRepository.countByRestaurant(30L)).thenReturn(3L);

        reviewService.deleteReview(4L);

        verify(reviewRepository).delete(review);
        assertEquals(3.2, restaurant.getAverageRating());
        assertEquals(3, restaurant.getTotalReviews());
        verify(restaurantRepository).save(restaurant);
        verify(restaurantRepository).save(restaurant);
    }

    @Test
    void deleteReview_nullAverageAfterDelete_setsZero() {
        Restaurant restaurant = restaurant(30L);
        restaurant.setAverageRating(5.0);
        restaurant.setTotalReviews(1);
        Review review = new Review();
        review.setCustomer(customer(1L));
        review.setRestaurant(restaurant);
        when(reviewRepository.findByIdWithDetails(4L)).thenReturn(Optional.of(review));
        when(securityUtils.getCurrentUserId()).thenReturn(1L);
        when(reviewRepository.getAverageRatingByRestaurant(30L)).thenReturn(null);
        when(reviewRepository.countByRestaurant(30L)).thenReturn(0L);

        reviewService.deleteReview(4L);

        assertEquals(0.0, restaurant.getAverageRating());
        assertEquals(0, restaurant.getTotalReviews());
        verify(restaurantRepository).save(restaurant);
    }

    // ==================== helpers ====================

    private ReviewRequest reviewRequest(Long orderId) {
        ReviewRequest request = new ReviewRequest();
        request.setOrderId(orderId);
        request.setRating(5);
        request.setComment("Great food");
        request.setFoodRating(4);
        request.setDeliveryRating(5);
        request.setImages(List.of("img1.jpg"));
        return request;
    }

    private Customer customer(Long id) {
        Customer customer = new Customer();
        customer.setId(id);
        customer.setFullName("Customer " + id);
        return customer;
    }

    private Restaurant restaurant(Long id) {
        Restaurant restaurant = new Restaurant();
        restaurant.setId(id);
        restaurant.setName("Spice Hub");
        restaurant.setAverageRating(0.0);
        restaurant.setTotalReviews(0);
        return restaurant;
    }

    private Order order(Long id, Customer customer, Order.OrderStatus status) {
        Order order = new Order();
        order.setId(id);
        order.setCustomer(customer);
        order.setRestaurant(restaurant(30L));
        order.setStatus(status);
        return order;
    }
}

package com.bhukkad.serviceImpl;

import com.bhukkad.dto.request.MenuItemRatingRequest;
import com.bhukkad.dto.response.MenuItemRatingResponse;
import com.bhukkad.entity.Customer;
import com.bhukkad.entity.MenuItem;
import com.bhukkad.entity.MenuItemRating;
import com.bhukkad.entity.Order;
import com.bhukkad.entity.OrderItem;
import com.bhukkad.exception.BusinessException;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.repository.CustomerRepository;
import com.bhukkad.repository.MenuItemRatingRepository;
import com.bhukkad.repository.MenuItemRepository;
import com.bhukkad.repository.OrderRepository;
import com.bhukkad.security.SecurityUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MenuItemRatingServiceImplTest {

    @Mock
    private MenuItemRatingRepository menuItemRatingRepository;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private MenuItemRepository menuItemRepository;

    @Mock
    private SecurityUtils securityUtils;

    @InjectMocks
    private MenuItemRatingServiceImpl service;

    private Customer customer(Long id) {
        Customer customer = new Customer();
        customer.setId(id);
        return customer;
    }

    private MenuItem menuItem(Long id) {
        MenuItem menuItem = new MenuItem();
        menuItem.setId(id);
        menuItem.setName("Biryani");
        return menuItem;
    }

    private Order deliveredOrder(Long orderId, Long customerId, Long menuItemId) {
        Order order = new Order();
        order.setId(orderId);
        order.setCustomer(customer(customerId));
        order.setStatus(Order.OrderStatus.DELIVERED);
        OrderItem orderItem = new OrderItem();
        orderItem.setId(1L);
        orderItem.setMenuItem(menuItem(menuItemId));
        order.setOrderItems(List.of(orderItem));
        return order;
    }

    private MenuItemRatingRequest request(Long orderId, Long menuItemId, Integer rating) {
        MenuItemRatingRequest request = new MenuItemRatingRequest();
        request.setOrderId(orderId);
        request.setMenuItemId(menuItemId);
        request.setRating(rating);
        request.setComment("Great!");
        return request;
    }

    // ---------- rateMenuItem ----------

    @Test
    void rateMenuItem_customerNotFound_throws() {
        when(securityUtils.getCurrentUserId()).thenReturn(10L);
        when(customerRepository.findById(10L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.rateMenuItem(request(100L, 99L, 5)));
    }

    @Test
    void rateMenuItem_orderNotFound_throws() {
        when(securityUtils.getCurrentUserId()).thenReturn(10L);
        when(customerRepository.findById(10L)).thenReturn(Optional.of(customer(10L)));
        when(orderRepository.findByIdWithDetails(100L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.rateMenuItem(request(100L, 99L, 5)));
    }

    @Test
    void rateMenuItem_notOwnOrder_throws() {
        when(securityUtils.getCurrentUserId()).thenReturn(10L);
        when(customerRepository.findById(10L)).thenReturn(Optional.of(customer(10L)));
        Order order = deliveredOrder(100L, 999L, 99L);
        when(orderRepository.findByIdWithDetails(100L)).thenReturn(Optional.of(order));

        assertThrows(BusinessException.class, () -> service.rateMenuItem(request(100L, 99L, 5)));
    }

    @Test
    void rateMenuItem_orderNotDelivered_throws() {
        when(securityUtils.getCurrentUserId()).thenReturn(10L);
        when(customerRepository.findById(10L)).thenReturn(Optional.of(customer(10L)));
        Order order = deliveredOrder(100L, 10L, 99L);
        order.setStatus(Order.OrderStatus.PLACED);
        when(orderRepository.findByIdWithDetails(100L)).thenReturn(Optional.of(order));

        assertThrows(BusinessException.class, () -> service.rateMenuItem(request(100L, 99L, 5)));
    }

    @Test
    void rateMenuItem_itemNotPartOfOrder_throws() {
        when(securityUtils.getCurrentUserId()).thenReturn(10L);
        when(customerRepository.findById(10L)).thenReturn(Optional.of(customer(10L)));
        Order order = deliveredOrder(100L, 10L, 55L);
        when(orderRepository.findByIdWithDetails(100L)).thenReturn(Optional.of(order));

        assertThrows(BusinessException.class, () -> service.rateMenuItem(request(100L, 99L, 5)));
    }

    @Test
    void rateMenuItem_alreadyRated_throws() {
        when(securityUtils.getCurrentUserId()).thenReturn(10L);
        when(customerRepository.findById(10L)).thenReturn(Optional.of(customer(10L)));
        Order order = deliveredOrder(100L, 10L, 99L);
        when(orderRepository.findByIdWithDetails(100L)).thenReturn(Optional.of(order));
        when(menuItemRatingRepository.findByOrderIdAndMenuItemId(100L, 99L))
                .thenReturn(Optional.of(new MenuItemRating()));

        assertThrows(BusinessException.class, () -> service.rateMenuItem(request(100L, 99L, 5)));
    }

    @Test
    void rateMenuItem_menuItemNotFound_throws() {
        when(securityUtils.getCurrentUserId()).thenReturn(10L);
        when(customerRepository.findById(10L)).thenReturn(Optional.of(customer(10L)));
        Order order = deliveredOrder(100L, 10L, 99L);
        when(orderRepository.findByIdWithDetails(100L)).thenReturn(Optional.of(order));
        when(menuItemRatingRepository.findByOrderIdAndMenuItemId(100L, 99L))
                .thenReturn(Optional.empty());
        when(menuItemRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.rateMenuItem(request(100L, 99L, 5)));
    }

    @Test
    void rateMenuItem_success_savesRatingAndUpdatesAverage() {
        when(securityUtils.getCurrentUserId()).thenReturn(10L);
        when(customerRepository.findById(10L)).thenReturn(Optional.of(customer(10L)));
        Order order = deliveredOrder(100L, 10L, 99L);
        when(orderRepository.findByIdWithDetails(100L)).thenReturn(Optional.of(order));
        when(menuItemRatingRepository.findByOrderIdAndMenuItemId(100L, 99L))
                .thenReturn(Optional.empty());
        MenuItem menuItem = menuItem(99L);
        when(menuItemRepository.findById(99L)).thenReturn(Optional.of(menuItem));
        when(menuItemRatingRepository.save(any(MenuItemRating.class))).thenAnswer(inv -> {
            MenuItemRating rating = inv.getArgument(0);
            rating.setId(7L);
            rating.setCreatedAt(LocalDateTime.of(2026, 8, 22, 10, 0));
            return rating;
        });
        when(menuItemRatingRepository.getAverageRatingByMenuItem(99L)).thenReturn(4.5);
        when(menuItemRatingRepository.countByMenuItem(99L)).thenReturn(3L);

        MenuItemRatingResponse response = service.rateMenuItem(request(100L, 99L, 5));

        ArgumentCaptor<MenuItemRating> captor = ArgumentCaptor.forClass(MenuItemRating.class);
        verify(menuItemRatingRepository).save(captor.capture());
        MenuItemRating saved = captor.getValue();
        assertEquals(customer(10L).getId(), saved.getCustomer().getId());
        assertEquals(menuItem, saved.getMenuItem());
        assertEquals(order, saved.getOrder());
        assertEquals(5, saved.getRating());
        assertEquals("Great!", saved.getComment());

        assertEquals(7L, response.getId());
        assertEquals(99L, response.getMenuItemId());
        assertEquals(100L, response.getOrderId());
        assertEquals(5, response.getRating());
        assertEquals("Great!", response.getComment());
        assertEquals("2026-08-22T10:00", response.getCreatedAt());

        assertEquals(4.5, menuItem.getAverageRating());
        assertEquals(3, menuItem.getTotalRatings());
        verify(menuItemRepository).save(menuItem);
    }

    @Test
    void rateMenuItem_nullAverageRating_updatesToZero() {
        when(securityUtils.getCurrentUserId()).thenReturn(10L);
        when(customerRepository.findById(10L)).thenReturn(Optional.of(customer(10L)));
        Order order = deliveredOrder(100L, 10L, 99L);
        when(orderRepository.findByIdWithDetails(100L)).thenReturn(Optional.of(order));
        when(menuItemRatingRepository.findByOrderIdAndMenuItemId(100L, 99L))
                .thenReturn(Optional.empty());
        MenuItem menuItem = menuItem(99L);
        when(menuItemRepository.findById(99L)).thenReturn(Optional.of(menuItem));
        when(menuItemRatingRepository.save(any(MenuItemRating.class))).thenAnswer(inv -> {
            MenuItemRating rating = inv.getArgument(0);
            rating.setId(8L);
            return rating;
        });
        when(menuItemRatingRepository.getAverageRatingByMenuItem(99L)).thenReturn(null);
        when(menuItemRatingRepository.countByMenuItem(99L)).thenReturn(1L);

        MenuItemRatingResponse response = service.rateMenuItem(request(100L, 99L, 4));

        assertEquals(0.0, menuItem.getAverageRating());
        assertEquals(1, menuItem.getTotalRatings());
        assertNull(response.getCreatedAt());
    }

    @Test
    void updateMenuItemAverageRating_menuItemMissing_doesNothing() {
        when(menuItemRatingRepository.getAverageRatingByMenuItem(999L)).thenReturn(3.0);
        when(menuItemRatingRepository.countByMenuItem(999L)).thenReturn(2L);
        when(menuItemRepository.findById(999L)).thenReturn(Optional.empty());

        ReflectionTestUtils.invokeMethod(service, "updateMenuItemAverageRating", 999L);

        verify(menuItemRepository, never()).save(any(MenuItem.class));
    }

    // ---------- getMenuItemRatings ----------

    @Test
    void getMenuItemRatings_menuItemNotFound_throws() {
        when(menuItemRepository.existsById(99L)).thenReturn(false);

        assertThrows(ResourceNotFoundException.class, () -> service.getMenuItemRatings(99L));
    }

    @Test
    void getMenuItemRatings_noRatings_returnsEmptyList() {
        when(menuItemRepository.existsById(99L)).thenReturn(true);
        when(menuItemRatingRepository.findByMenuItemIdOrderByCreatedAtDesc(99L)).thenReturn(List.of());

        assertEquals(0, service.getMenuItemRatings(99L).size());
    }

    @Test
    void getMenuItemRatings_mapsRatings() {
        when(menuItemRepository.existsById(99L)).thenReturn(true);

        MenuItemRating rating1 = new MenuItemRating();
        rating1.setId(1L);
        rating1.setMenuItem(menuItem(99L));
        rating1.setOrder(deliveredOrder(100L, 10L, 99L));
        rating1.setRating(5);
        rating1.setComment("Amazing");
        rating1.setCreatedAt(LocalDateTime.of(2026, 8, 22, 9, 0));

        MenuItemRating rating2 = new MenuItemRating();
        rating2.setId(2L);
        rating2.setMenuItem(menuItem(99L));
        rating2.setOrder(deliveredOrder(100L, 10L, 99L));
        rating2.setRating(3);
        rating2.setComment(null);
        rating2.setCreatedAt(null);

        when(menuItemRatingRepository.findByMenuItemIdOrderByCreatedAtDesc(99L))
                .thenReturn(List.of(rating1, rating2));

        List<MenuItemRatingResponse> responses = service.getMenuItemRatings(99L);

        assertEquals(2, responses.size());
        MenuItemRatingResponse first = responses.get(0);
        assertEquals(1L, first.getId());
        assertEquals(99L, first.getMenuItemId());
        assertEquals(100L, first.getOrderId());
        assertEquals(5, first.getRating());
        assertEquals("Amazing", first.getComment());
        assertEquals("2026-08-22T09:00", first.getCreatedAt());

        MenuItemRatingResponse second = responses.get(1);
        assertEquals(3, second.getRating());
        assertNull(second.getComment());
        assertNull(second.getCreatedAt());
    }
}

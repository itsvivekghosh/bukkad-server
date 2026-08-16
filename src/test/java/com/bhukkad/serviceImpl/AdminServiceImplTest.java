package com.bhukkad.serviceImpl;

import com.bhukkad.cache.RedisCacheService;
import com.bhukkad.entity.DeliveryAgent;
import com.bhukkad.entity.Order;
import com.bhukkad.entity.Restaurant;
import com.bhukkad.entity.RestaurantOwner;
import com.bhukkad.entity.User;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.repository.CustomerRepository;
import com.bhukkad.repository.DeliveryAgentRepository;
import com.bhukkad.repository.OrderRepository;
import com.bhukkad.repository.PaymentRepository;
import com.bhukkad.repository.RestaurantOwnerRepository;
import com.bhukkad.repository.RestaurantRepository;
import com.bhukkad.repository.ReviewRepository;
import com.bhukkad.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminServiceImplTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private CustomerRepository customerRepository;
    @Mock
    private RestaurantOwnerRepository restaurantOwnerRepository;
    @Mock
    private DeliveryAgentRepository deliveryAgentRepository;
    @Mock
    private RestaurantRepository restaurantRepository;
    @Mock
    private OrderRepository orderRepository;
    @Mock
    private ReviewRepository reviewRepository;
    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private RedisCacheService cacheService;

    @InjectMocks
    private AdminServiceImpl adminService;

    @BeforeEach
    void setUpCache() {
        lenient().when(cacheService.getOrCompute(anyString(), eq(Map.class), anyLong(), any()))
                .thenAnswer(invocation -> {
                    Supplier<?> supplier = invocation.getArgument(3);
                    return supplier.get();
                });
    }

    // ==================== getDashboardStats ====================

    @Test
    void getDashboardStats_nullRevenueBecomesZero_andTodayStartsAtMidnight() {
        when(userRepository.count()).thenReturn(10L);
        when(customerRepository.count()).thenReturn(6L);
        when(restaurantOwnerRepository.count()).thenReturn(3L);
        when(deliveryAgentRepository.count()).thenReturn(1L);
        when(restaurantRepository.count()).thenReturn(4L);
        when(restaurantRepository.findByIsActiveTrue()).thenReturn(List.of(restaurant(1L), restaurant(2L)));
        when(orderRepository.count()).thenReturn(20L);
        when(orderRepository.countByCreatedAtAfter(any(LocalDateTime.class))).thenReturn(2L);
        when(orderRepository.sumTotalAmount()).thenReturn(null);
        when(orderRepository.sumTotalAmountAfter(any(LocalDateTime.class))).thenReturn(null);
        when(orderRepository.findTop10ByOrderByCreatedAtDesc()).thenReturn(List.of());

        Map<String, Object> stats = adminService.getDashboardStats();

        assertEquals(10L, stats.get("totalUsers"));
        assertEquals(6L, stats.get("totalCustomers"));
        assertEquals(3L, stats.get("totalOwners"));
        assertEquals(1L, stats.get("totalAgents"));
        assertEquals(4L, stats.get("totalRestaurants"));
        assertEquals(2L, stats.get("activeRestaurants"));
        assertEquals(20L, stats.get("totalOrders"));
        assertEquals(2L, stats.get("todayOrders"));
        assertEquals(0.0, stats.get("totalRevenue"));
        assertEquals(0.0, stats.get("todayRevenue"));
        assertEquals(List.of(), stats.get("recentOrders"));

        ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(orderRepository).countByCreatedAtAfter(captor.capture());
        LocalDateTime todayStart = captor.getValue();
        assertEquals(0, todayStart.getHour());
        assertEquals(0, todayStart.getMinute());
        assertEquals(0, todayStart.getSecond());
        assertEquals(LocalDate.now(), todayStart.toLocalDate());
    }

    @Test
    void getDashboardStats_nonNullRevenueAndRecentOrders_mapsCreatedAtNullAndPresent() {
        when(userRepository.count()).thenReturn(1L);
        when(customerRepository.count()).thenReturn(1L);
        when(restaurantOwnerRepository.count()).thenReturn(1L);
        when(deliveryAgentRepository.count()).thenReturn(1L);
        when(restaurantRepository.count()).thenReturn(1L);
        when(restaurantRepository.findByIsActiveTrue()).thenReturn(List.of());
        when(orderRepository.count()).thenReturn(1L);
        when(orderRepository.countByCreatedAtAfter(any(LocalDateTime.class))).thenReturn(1L);
        when(orderRepository.sumTotalAmount()).thenReturn(500.0);
        when(orderRepository.sumTotalAmountAfter(any(LocalDateTime.class))).thenReturn(50.0);

        Order withDate = order(1L, Order.OrderStatus.DELIVERED, 200.0);
        withDate.setCreatedAt(LocalDateTime.of(2026, 8, 14, 10, 0));
        Order withoutDate = order(2L, Order.OrderStatus.PLACED, 100.0);
        withoutDate.setCreatedAt(null);
        when(orderRepository.findTop10ByOrderByCreatedAtDesc()).thenReturn(List.of(withDate, withoutDate));

        Map<String, Object> stats = adminService.getDashboardStats();

        assertEquals(500.0, stats.get("totalRevenue"));
        assertEquals(50.0, stats.get("todayRevenue"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> recent = (List<Map<String, Object>>) stats.get("recentOrders");
        assertEquals(2, recent.size());
        assertEquals(1L, recent.get(0).get("id"));
        assertEquals("ORD-1", recent.get(0).get("orderNumber"));
        assertEquals("DELIVERED", recent.get(0).get("status"));
        assertEquals(200.0, recent.get(0).get("totalAmount"));
        assertEquals(withDate.getCreatedAt().toString(), recent.get(0).get("createdAt"));
        assertNull(recent.get(1).get("createdAt"));
    }

    // ==================== getAllUsers ====================

    @Test
    void getAllUsers_nonEmptyRole_winsOverSearch() {
        User user = user(1L, User.UserRole.CUSTOMER);
        Page<User> page = new PageImpl<>(List.of(user), pageRequest(0, 10), 1);
        when(userRepository.findByRole(eq(User.UserRole.CUSTOMER), any(Pageable.class))).thenReturn(page);

        Map<String, Object> result = adminService.getAllUsers(0, 10, "customer", "ignored-search");

        verify(userRepository).findByRole(eq(User.UserRole.CUSTOMER), any(Pageable.class));
        verify(userRepository, never()).findByFullNameContainingOrEmailContaining(any(), any(), any());
        verify(userRepository, never()).findAll(any(Pageable.class));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> users = (List<Map<String, Object>>) result.get("users");
        assertEquals(1, users.size());
        assertEquals(1L, users.get(0).get("id"));
        assertEquals("user1@test.com", users.get(0).get("email"));
        assertEquals("User 1", users.get(0).get("fullName"));
        assertEquals("9990000001", users.get(0).get("phoneNumber"));
        assertEquals("CUSTOMER", users.get(0).get("role"));
        assertEquals(true, users.get(0).get("active"));
        assertEquals(false, users.get(0).get("emailVerified"));
        assertEquals(user.getCreatedAt().toString(), users.get(0).get("createdAt"));
        assertEquals(1L, result.get("totalElements"));
        assertEquals(1, result.get("totalPages"));
        assertEquals(0, result.get("currentPage"));
        assertEquals(10, result.get("size"));
    }

    @Test
    void getAllUsers_emptyRoleWithSearch_usesNameOrEmailQuery() {
        User user = user(2L, User.UserRole.ADMIN);
        user.setCreatedAt(null);
        Page<User> page = new PageImpl<>(List.of(user), pageRequest(1, 5), 6);
        when(userRepository.findByFullNameContainingOrEmailContaining(eq("ada"), eq("ada"), any(Pageable.class)))
                .thenReturn(page);

        Map<String, Object> result = adminService.getAllUsers(1, 5, "", "ada");

        verify(userRepository).findByFullNameContainingOrEmailContaining(eq("ada"), eq("ada"), any(Pageable.class));
        verify(userRepository, never()).findByRole(any(), any());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> users = (List<Map<String, Object>>) result.get("users");
        assertNull(users.get(0).get("createdAt"));
        assertEquals(6L, result.get("totalElements"));
        assertEquals(2, result.get("totalPages"));
        assertEquals(1, result.get("currentPage"));
        assertEquals(5, result.get("size"));
    }

    @Test
    void getAllUsers_nullRoleAndSearch_findsAll() {
        Page<User> page = new PageImpl<>(List.of(), pageRequest(0, 10), 0);
        when(userRepository.findAll(any(Pageable.class))).thenReturn(page);

        Map<String, Object> result = adminService.getAllUsers(0, 10, null, null);

        verify(userRepository).findAll(any(Pageable.class));
        assertEquals(0L, result.get("totalElements"));
        assertTrue(((List<?>) result.get("users")).isEmpty());
    }

    @Test
    void getAllUsers_emptyRoleAndEmptySearch_findsAll() {
        Page<User> page = new PageImpl<>(List.of(), pageRequest(0, 10), 0);
        when(userRepository.findAll(any(Pageable.class))).thenReturn(page);

        adminService.getAllUsers(0, 10, "", "");

        verify(userRepository).findAll(any(Pageable.class));
    }

    @Test
    void getAllUsers_nullRoleWithSearch_usesSearch() {
        Page<User> page = new PageImpl<>(List.of(), pageRequest(0, 10), 0);
        when(userRepository.findByFullNameContainingOrEmailContaining(eq("x"), eq("x"), any(Pageable.class)))
                .thenReturn(page);

        adminService.getAllUsers(0, 10, null, "x");

        verify(userRepository).findByFullNameContainingOrEmailContaining(eq("x"), eq("x"), any(Pageable.class));
    }

    // ==================== activate / deactivate ====================

    @Test
    void activateUser_notFound_throws() {
        when(userRepository.findById(1L)).thenReturn(Optional.empty());
        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                () -> adminService.activateUser(1L));
        assertEquals("User not found", ex.getMessage());
    }

    @Test
    void activateUser_setsActiveTrue() {
        User user = user(1L, User.UserRole.CUSTOMER);
        user.setActive(false);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        adminService.activateUser(1L);

        assertTrue(user.getActive());
        verify(userRepository).save(user);
    }

    @Test
    void deactivateUser_notFound_throws() {
        when(userRepository.findById(1L)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> adminService.deactivateUser(1L));
    }

    @Test
    void deactivateUser_setsActiveFalse() {
        User user = user(1L, User.UserRole.CUSTOMER);
        user.setActive(true);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        adminService.deactivateUser(1L);

        assertFalse(user.getActive());
        verify(userRepository).save(user);
    }

    // ==================== verify owner / agent ====================

    @Test
    void verifyRestaurantOwner_notFound_throws() {
        when(restaurantOwnerRepository.findById(1L)).thenReturn(Optional.empty());
        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                () -> adminService.verifyRestaurantOwner(1L));
        assertEquals("Owner not found", ex.getMessage());
    }

    @Test
    void verifyRestaurantOwner_setsVerifiedTrue() {
        RestaurantOwner owner = new RestaurantOwner();
        owner.setId(1L);
        owner.setVerified(false);
        when(restaurantOwnerRepository.findById(1L)).thenReturn(Optional.of(owner));

        adminService.verifyRestaurantOwner(1L);

        assertTrue(owner.getVerified());
        verify(restaurantOwnerRepository).save(owner);
    }

    @Test
    void verifyDeliveryAgent_notFound_throws() {
        when(deliveryAgentRepository.findById(1L)).thenReturn(Optional.empty());
        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                () -> adminService.verifyDeliveryAgent(1L));
        assertEquals("Agent not found", ex.getMessage());
    }

    @Test
    void verifyDeliveryAgent_setsVerifiedTrue() {
        DeliveryAgent agent = new DeliveryAgent();
        agent.setId(1L);
        agent.setVerified(false);
        when(deliveryAgentRepository.findById(1L)).thenReturn(Optional.of(agent));

        adminService.verifyDeliveryAgent(1L);

        assertTrue(agent.getVerified());
        verify(deliveryAgentRepository).save(agent);
    }

    // ==================== getAllOrders ====================

    @Test
    void getAllOrders_statusFilter_usesFindByStatus() {
        Order order = order(9L, Order.OrderStatus.PLACED, 99.0);
        Page<Order> page = new PageImpl<>(List.of(order), pageRequest(0, 10), 1);
        when(orderRepository.findByStatus(eq(Order.OrderStatus.PLACED), any(Pageable.class))).thenReturn(page);

        Map<String, Object> result = adminService.getAllOrders(0, 10, "placed");

        verify(orderRepository).findByStatus(eq(Order.OrderStatus.PLACED), any(Pageable.class));
        verify(orderRepository, never()).findAll(any(Pageable.class));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> orders = (List<Map<String, Object>>) result.get("orders");
        assertEquals("PLACED", orders.get(0).get("status"));
        assertEquals(1L, result.get("totalElements"));
        assertEquals(1, result.get("totalPages"));
        assertEquals(0, result.get("currentPage"));
        assertFalse(result.containsKey("size"));
    }

    @Test
    void getAllOrders_nullStatus_findsAll() {
        Page<Order> page = new PageImpl<>(List.of(), pageRequest(0, 10), 0);
        when(orderRepository.findAll(any(Pageable.class))).thenReturn(page);

        adminService.getAllOrders(0, 10, null);

        verify(orderRepository).findAll(any(Pageable.class));
    }

    @Test
    void getAllOrders_emptyStatus_findsAll() {
        Page<Order> page = new PageImpl<>(List.of(), pageRequest(0, 10), 0);
        when(orderRepository.findAll(any(Pageable.class))).thenReturn(page);

        adminService.getAllOrders(0, 10, "");

        verify(orderRepository).findAll(any(Pageable.class));
    }

    // ==================== getAllRestaurants ====================

    @Test
    void getAllRestaurants_activeTrue_filters() {
        Restaurant restaurant = restaurant(1L);
        restaurant.setCreatedAt(LocalDateTime.of(2026, 1, 1, 0, 0));
        Page<Restaurant> page = new PageImpl<>(List.of(restaurant), pageRequest(0, 10), 1);
        when(restaurantRepository.findByIsActive(eq(true), any(Pageable.class))).thenReturn(page);

        Map<String, Object> result = adminService.getAllRestaurants(0, 10, true);

        verify(restaurantRepository).findByIsActive(eq(true), any(Pageable.class));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> restaurants = (List<Map<String, Object>>) result.get("restaurants");
        assertEquals(1L, restaurants.get(0).get("id"));
        assertEquals("Spice Hub", restaurants.get(0).get("name"));
        assertEquals(true, restaurants.get(0).get("isActive"));
        assertEquals(true, restaurants.get(0).get("isOpen"));
        assertEquals(4.2, restaurants.get(0).get("averageRating"));
        assertEquals(7, restaurants.get(0).get("totalReviews"));
        assertEquals(restaurant.getCreatedAt().toString(), restaurants.get(0).get("createdAt"));
        assertEquals(1L, result.get("totalElements"));
        assertEquals(1, result.get("totalPages"));
        assertEquals(0, result.get("currentPage"));
    }

    @Test
    void getAllRestaurants_activeFalse_filters() {
        Restaurant restaurant = restaurant(2L);
        restaurant.setIsActive(false);
        restaurant.setCreatedAt(null);
        Page<Restaurant> page = new PageImpl<>(List.of(restaurant), pageRequest(0, 10), 1);
        when(restaurantRepository.findByIsActive(eq(false), any(Pageable.class))).thenReturn(page);

        Map<String, Object> result = adminService.getAllRestaurants(0, 10, false);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> restaurants = (List<Map<String, Object>>) result.get("restaurants");
        assertNull(restaurants.get(0).get("createdAt"));
        assertEquals(false, restaurants.get(0).get("isActive"));
    }

    @Test
    void getAllRestaurants_activeNull_findsAll() {
        Page<Restaurant> page = new PageImpl<>(List.of(), pageRequest(0, 10), 0);
        when(restaurantRepository.findAll(any(Pageable.class))).thenReturn(page);

        adminService.getAllRestaurants(0, 10, null);

        verify(restaurantRepository).findAll(any(Pageable.class));
        verify(restaurantRepository, never()).findByIsActive(any(), any());
    }

    // ==================== approve / suspend ====================

    @Test
    void approveRestaurant_notFound_throws() {
        when(restaurantRepository.findById(1L)).thenReturn(Optional.empty());
        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                () -> adminService.approveRestaurant(1L));
        assertEquals("Restaurant not found", ex.getMessage());
    }

    @Test
    void approveRestaurant_setsIsActiveTrue() {
        Restaurant restaurant = restaurant(1L);
        restaurant.setIsActive(false);
        when(restaurantRepository.findById(1L)).thenReturn(Optional.of(restaurant));

        adminService.approveRestaurant(1L);

        assertTrue(restaurant.getIsActive());
        verify(restaurantRepository).save(restaurant);
    }

    @Test
    void suspendRestaurant_notFound_throws() {
        when(restaurantRepository.findById(1L)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> adminService.suspendRestaurant(1L));
    }

    @Test
    void suspendRestaurant_setsInactiveAndClosed() {
        Restaurant restaurant = restaurant(1L);
        restaurant.setIsActive(true);
        restaurant.setIsOpen(true);
        when(restaurantRepository.findById(1L)).thenReturn(Optional.of(restaurant));

        adminService.suspendRestaurant(1L);

        assertFalse(restaurant.getIsActive());
        assertFalse(restaurant.getIsOpen());
        verify(restaurantRepository).save(restaurant);
    }

    // ==================== getRevenueStats ====================

    @Test
    void getRevenueStats_zeroOrders_averageOrderValueIsZero() {
        when(orderRepository.sumTotalAmountAfter(any(LocalDateTime.class))).thenReturn(100.0);
        when(orderRepository.countByCreatedAtAfter(any(LocalDateTime.class))).thenReturn(0L);
        when(orderRepository.countByStatusAndCreatedAtAfter(eq(Order.OrderStatus.DELIVERED), any(LocalDateTime.class)))
                .thenReturn(0L);
        when(orderRepository.countByStatusAndCreatedAtAfter(eq(Order.OrderStatus.CANCELLED), any(LocalDateTime.class)))
                .thenReturn(0L);

        Map<String, Object> revenue = adminService.getRevenueStats(7);

        assertEquals("7 days", revenue.get("period"));
        assertEquals(100.0, revenue.get("totalRevenue"));
        assertEquals(0L, revenue.get("totalOrders"));
        assertEquals(0.0, revenue.get("averageOrderValue"));
        ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(orderRepository).sumTotalAmountAfter(captor.capture());
        assertTrue(captor.getValue().isBefore(LocalDateTime.now().plusSeconds(1)));
        assertTrue(captor.getValue().isAfter(LocalDateTime.now().minusDays(7).minusMinutes(1)));
    }

    @Test
    void getRevenueStats_ordersWithRevenue_computesAverage() {
        when(orderRepository.sumTotalAmountAfter(any(LocalDateTime.class))).thenReturn(200.0);
        when(orderRepository.countByCreatedAtAfter(any(LocalDateTime.class))).thenReturn(4L);
        when(orderRepository.countByStatusAndCreatedAtAfter(eq(Order.OrderStatus.DELIVERED), any(LocalDateTime.class)))
                .thenReturn(3L);
        when(orderRepository.countByStatusAndCreatedAtAfter(eq(Order.OrderStatus.CANCELLED), any(LocalDateTime.class)))
                .thenReturn(1L);

        Map<String, Object> revenue = adminService.getRevenueStats(30);

        assertEquals("30 days", revenue.get("period"));
        assertEquals(200.0, revenue.get("totalRevenue"));
        assertEquals(4L, revenue.get("totalOrders"));
        assertEquals(3L, revenue.get("deliveredOrders"));
        assertEquals(1L, revenue.get("cancelledOrders"));
        assertEquals(50.0, revenue.get("averageOrderValue"));
    }

    @Test
    void getRevenueStats_ordersWithNullRevenue_averageAndTotalAreZero() {
        when(orderRepository.sumTotalAmountAfter(any(LocalDateTime.class))).thenReturn(null);
        when(orderRepository.countByCreatedAtAfter(any(LocalDateTime.class))).thenReturn(5L);
        when(orderRepository.countByStatusAndCreatedAtAfter(any(Order.OrderStatus.class), any(LocalDateTime.class)))
                .thenReturn(0L);

        Map<String, Object> revenue = adminService.getRevenueStats(1);

        assertEquals(0.0, revenue.get("totalRevenue"));
        assertEquals(5L, revenue.get("totalOrders"));
        assertEquals(0.0, revenue.get("averageOrderValue"));
    }

    // ==================== getAnalytics ====================

    @Test
    void getAnalytics_countsEveryStatusAndRole_andMapsTopRestaurants() {
        for (Order.OrderStatus status : Order.OrderStatus.values()) {
            when(orderRepository.countByStatus(status)).thenReturn((long) status.ordinal() + 1);
        }
        for (User.UserRole role : User.UserRole.values()) {
            when(userRepository.countByRole(role)).thenReturn((long) role.ordinal() + 10);
        }
        Restaurant top = restaurant(11L);
        top.setCreatedAt(LocalDateTime.of(2026, 2, 2, 2, 2));
        Restaurant undated = restaurant(12L);
        undated.setCreatedAt(null);
        when(restaurantRepository.findTop10ByIsActiveTrueOrderByAverageRatingDesc())
                .thenReturn(List.of(top, undated));

        Map<String, Object> analytics = adminService.getAnalytics();

        @SuppressWarnings("unchecked")
        Map<String, Long> ordersByStatus = (Map<String, Long>) analytics.get("ordersByStatus");
        for (Order.OrderStatus status : Order.OrderStatus.values()) {
            assertEquals((long) status.ordinal() + 1, ordersByStatus.get(status.name()));
        }
        @SuppressWarnings("unchecked")
        Map<String, Long> usersByRole = (Map<String, Long>) analytics.get("usersByRole");
        for (User.UserRole role : User.UserRole.values()) {
            assertEquals((long) role.ordinal() + 10, usersByRole.get(role.name()));
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> topRestaurants = (List<Map<String, Object>>) analytics.get("topRestaurants");
        assertEquals(2, topRestaurants.size());
        assertEquals(11L, topRestaurants.get(0).get("id"));
        assertEquals(top.getCreatedAt().toString(), topRestaurants.get(0).get("createdAt"));
        assertNull(topRestaurants.get(1).get("createdAt"));
    }

    // ==================== helpers ====================

    private PageRequest pageRequest(int page, int size) {
        return PageRequest.of(page, size, Sort.by("createdAt").descending());
    }

    private User user(Long id, User.UserRole role) {
        User user = new User();
        user.setId(id);
        user.setEmail("user" + id + "@test.com");
        user.setFullName("User " + id);
        user.setPhoneNumber("999000000" + id);
        user.setRole(role);
        user.setActive(true);
        user.setEmailVerified(false);
        user.setCreatedAt(LocalDateTime.of(2026, 8, 1, 9, 0));
        return user;
    }

    private Order order(Long id, Order.OrderStatus status, Double total) {
        Order order = new Order();
        order.setId(id);
        order.setOrderNumber("ORD-" + id);
        order.setStatus(status);
        order.setTotalAmount(total);
        order.setCreatedAt(LocalDateTime.of(2026, 8, 14, 8, 0));
        return order;
    }

    private Restaurant restaurant(Long id) {
        Restaurant restaurant = new Restaurant();
        restaurant.setId(id);
        restaurant.setName("Spice Hub");
        restaurant.setIsActive(true);
        restaurant.setIsOpen(true);
        restaurant.setAverageRating(4.2);
        restaurant.setTotalReviews(7);
        restaurant.setCreatedAt(LocalDateTime.of(2026, 3, 1, 0, 0));
        return restaurant;
    }
}

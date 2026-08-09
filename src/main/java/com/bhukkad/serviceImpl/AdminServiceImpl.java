package com.bhukkad.serviceImpl;

import com.bhukkad.entity.*;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.repository.*;
import com.bhukkad.service.AdminService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AdminServiceImpl implements AdminService {

    private static final Logger log = LoggerFactory.getLogger(AdminServiceImpl.class);

    private final UserRepository userRepository;
    private final CustomerRepository customerRepository;
    private final RestaurantOwnerRepository restaurantOwnerRepository;
    private final DeliveryAgentRepository deliveryAgentRepository;
    private final RestaurantRepository restaurantRepository;
    private final OrderRepository orderRepository;
    private final ReviewRepository reviewRepository;
    private final PaymentRepository paymentRepository;

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> getDashboardStats() {
        Map<String, Object> stats = new LinkedHashMap<>();

        // User counts
        stats.put("totalUsers", userRepository.count());
        stats.put("totalCustomers", customerRepository.count());
        stats.put("totalOwners", restaurantOwnerRepository.count());
        stats.put("totalAgents", deliveryAgentRepository.count());

        // Restaurant counts
        long totalRestaurants = restaurantRepository.count();
        long activeRestaurants = restaurantRepository.findByIsActiveTrue().size();
        stats.put("totalRestaurants", totalRestaurants);
        stats.put("activeRestaurants", activeRestaurants);

        // Order counts
        long totalOrders = orderRepository.count();
        stats.put("totalOrders", totalOrders);

        // Today's stats
        LocalDateTime todayStart = LocalDateTime.now().withHour(0).withMinute(0).withSecond(0);
        long todayOrders = orderRepository.countByCreatedAtAfter(todayStart);
        stats.put("todayOrders", todayOrders);

        // Revenue
        Double totalRevenue = orderRepository.sumTotalAmount();
        stats.put("totalRevenue", totalRevenue != null ? totalRevenue : 0.0);

        Double todayRevenue = orderRepository.sumTotalAmountAfter(todayStart);
        stats.put("todayRevenue", todayRevenue != null ? todayRevenue : 0.0);

        // Recent orders
        List<Map<String, Object>> recentOrders = orderRepository
                .findTop10ByOrderByCreatedAtDesc()
                .stream()
                .map(this::mapOrderToSummary)
                .collect(Collectors.toList());
        stats.put("recentOrders", recentOrders);

        return stats;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> getAllUsers(int page, int size, String role, String search) {
        Map<String, Object> result = new LinkedHashMap<>();

        PageRequest pageRequest = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<User> users;

        if (role != null && !role.isEmpty()) {
            User.UserRole userRole = User.UserRole.valueOf(role.toUpperCase());
            users = userRepository.findByRole(userRole, pageRequest);
        } else if (search != null && !search.isEmpty()) {
            users = userRepository.findByFullNameContainingOrEmailContaining(search, search, pageRequest);
        } else {
            users = userRepository.findAll(pageRequest);
        }

        result.put("users", users.getContent().stream()
                .map(this::mapUserToSummary)
                .collect(Collectors.toList()));
        result.put("totalElements", users.getTotalElements());
        result.put("totalPages", users.getTotalPages());
        result.put("currentPage", users.getNumber());
        result.put("size", users.getSize());

        return result;
    }

    @Override
    @Transactional
    public void activateUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        user.setActive(true);
        userRepository.save(user);
        log.info("User activated: {}", userId);
    }

    @Override
    @Transactional
    public void deactivateUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        user.setActive(false);
        userRepository.save(user);
        log.info("User deactivated: {}", userId);
    }

    @Override
    @Transactional
    public void verifyRestaurantOwner(Long ownerId) {
        RestaurantOwner owner = restaurantOwnerRepository.findById(ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("Owner not found"));
        owner.setVerified(true);
        restaurantOwnerRepository.save(owner);
        log.info("Restaurant owner verified: {}", ownerId);
    }

    @Override
    @Transactional
    public void verifyDeliveryAgent(Long agentId) {
        DeliveryAgent agent = deliveryAgentRepository.findById(agentId)
                .orElseThrow(() -> new ResourceNotFoundException("Agent not found"));
        agent.setVerified(true);
        deliveryAgentRepository.save(agent);
        log.info("Delivery agent verified: {}", agentId);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> getAllOrders(int page, int size, String status) {
        Map<String, Object> result = new LinkedHashMap<>();

        PageRequest pageRequest = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<Order> orders;

        if (status != null && !status.isEmpty()) {
            Order.OrderStatus orderStatus = Order.OrderStatus.valueOf(status.toUpperCase());
            orders = orderRepository.findByStatus(orderStatus, pageRequest);
        } else {
            orders = orderRepository.findAll(pageRequest);
        }

        result.put("orders", orders.getContent().stream()
                .map(this::mapOrderToSummary)
                .collect(Collectors.toList()));
        result.put("totalElements", orders.getTotalElements());
        result.put("totalPages", orders.getTotalPages());
        result.put("currentPage", orders.getNumber());

        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> getAllRestaurants(int page, int size, Boolean active) {
        Map<String, Object> result = new LinkedHashMap<>();

        PageRequest pageRequest = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<Restaurant> restaurants;

        if (active != null) {
            restaurants = restaurantRepository.findByIsActive(active, pageRequest);
        } else {
            restaurants = restaurantRepository.findAll(pageRequest);
        }

        result.put("restaurants", restaurants.getContent().stream()
                .map(this::mapRestaurantToSummary)
                .collect(Collectors.toList()));
        result.put("totalElements", restaurants.getTotalElements());
        result.put("totalPages", restaurants.getTotalPages());
        result.put("currentPage", restaurants.getNumber());

        return result;
    }

    @Override
    @Transactional
    public void approveRestaurant(Long restaurantId) {
        Restaurant restaurant = restaurantRepository.findById(restaurantId)
                .orElseThrow(() -> new ResourceNotFoundException("Restaurant not found"));
        restaurant.setIsActive(true);
        restaurantRepository.save(restaurant);
        log.info("Restaurant approved: {}", restaurantId);
    }

    @Override
    @Transactional
    public void suspendRestaurant(Long restaurantId) {
        Restaurant restaurant = restaurantRepository.findById(restaurantId)
                .orElseThrow(() -> new ResourceNotFoundException("Restaurant not found"));
        restaurant.setIsActive(false);
        restaurant.setIsOpen(false);
        restaurantRepository.save(restaurant);
        log.info("Restaurant suspended: {}", restaurantId);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> getRevenueStats(int days) {
        Map<String, Object> revenue = new LinkedHashMap<>();

        LocalDateTime startDate = LocalDateTime.now().minusDays(days);

        Double totalRevenue = orderRepository.sumTotalAmountAfter(startDate);
        Long totalOrders = orderRepository.countByCreatedAtAfter(startDate);
        Long deliveredOrders = orderRepository.countByStatusAndCreatedAtAfter(
                Order.OrderStatus.DELIVERED, startDate);
        Long cancelledOrders = orderRepository.countByStatusAndCreatedAtAfter(
                Order.OrderStatus.CANCELLED, startDate);

        revenue.put("period", days + " days");
        revenue.put("totalRevenue", totalRevenue != null ? totalRevenue : 0.0);
        revenue.put("totalOrders", totalOrders);
        revenue.put("deliveredOrders", deliveredOrders);
        revenue.put("cancelledOrders", cancelledOrders);
        revenue.put("averageOrderValue",
                totalOrders > 0 ? (totalRevenue != null ? totalRevenue / totalOrders : 0.0) : 0.0);

        return revenue;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> getAnalytics() {
        Map<String, Object> analytics = new LinkedHashMap<>();

        // Order status distribution
        Map<String, Long> ordersByStatus = new LinkedHashMap<>();
        for (Order.OrderStatus status : Order.OrderStatus.values()) {
            ordersByStatus.put(status.name(), orderRepository.countByStatus(status));
        }
        analytics.put("ordersByStatus", ordersByStatus);

        // User role distribution
        Map<String, Long> usersByRole = new LinkedHashMap<>();
        for (User.UserRole role : User.UserRole.values()) {
            usersByRole.put(role.name(), userRepository.countByRole(role));
        }
        analytics.put("usersByRole", usersByRole);

        // Top rated restaurants
        List<Map<String, Object>> topRestaurants = restaurantRepository
                .findTop10ByIsActiveTrueOrderByAverageRatingDesc()
                .stream()
                .map(this::mapRestaurantToSummary)
                .collect(Collectors.toList());
        analytics.put("topRestaurants", topRestaurants);

        return analytics;
    }

    // ==================== MAPPERS ====================

    private Map<String, Object> mapUserToSummary(User user) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", user.getId());
        map.put("email", user.getEmail());
        map.put("fullName", user.getFullName());
        map.put("phoneNumber", user.getPhoneNumber());
        map.put("role", user.getRole().name());
        map.put("active", user.getActive());
        map.put("emailVerified", user.getEmailVerified());
        map.put("createdAt", user.getCreatedAt() != null ? user.getCreatedAt().toString() : null);
        return map;
    }

    private Map<String, Object> mapOrderToSummary(Order order) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", order.getId());
        map.put("orderNumber", order.getOrderNumber());
        map.put("status", order.getStatus().name());
        map.put("totalAmount", order.getTotalAmount());
        map.put("createdAt", order.getCreatedAt() != null ? order.getCreatedAt().toString() : null);
        return map;
    }

    private Map<String, Object> mapRestaurantToSummary(Restaurant restaurant) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", restaurant.getId());
        map.put("name", restaurant.getName());
        map.put("isActive", restaurant.getIsActive());
        map.put("isOpen", restaurant.getIsOpen());
        map.put("averageRating", restaurant.getAverageRating());
        map.put("totalReviews", restaurant.getTotalReviews());
        map.put("createdAt", restaurant.getCreatedAt() != null ? restaurant.getCreatedAt().toString() : null);
        return map;
    }
}
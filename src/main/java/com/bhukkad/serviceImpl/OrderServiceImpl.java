package com.bhukkad.serviceImpl;

import com.bhukkad.dto.request.OrderItemRequest;
import com.bhukkad.dto.request.OrderRequest;
import com.bhukkad.dto.response.AddressResponse;
import com.bhukkad.dto.response.DeliveryAgentResponse;
import com.bhukkad.dto.response.OrderItemResponse;
import com.bhukkad.dto.response.OrderResponse;
import com.bhukkad.entity.*;
import com.bhukkad.repository.*;
import com.bhukkad.dto.response.*;
import com.bhukkad.entity.*;
import com.bhukkad.exception.BusinessException;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.exception.UnauthorizedException;
import com.bhukkad.repository.*;
import com.bhukkad.security.SecurityUtils;
import com.bhukkad.service.CouponService;
import com.bhukkad.service.OrderService;
import com.bhukkad.service.PaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    private final OrderRepository orderRepository;
    private final CustomerRepository customerRepository;
    private final RestaurantRepository restaurantRepository;
    private final MenuItemRepository menuItemRepository;
    private final AddressRepository addressRepository;
    private final CartRepository cartRepository;
    private final CouponService couponService;
    private final PaymentService paymentService;
    private final SecurityUtils securityUtils;

    private static final double TAX_RATE = 0.05; // 5% tax

    @Override
    @Transactional
    public OrderResponse createOrder(OrderRequest request) {
        // Get current customer
        Customer customer = customerRepository.findById(securityUtils.getCurrentUserId())
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found"));

        // Get restaurant
        Restaurant restaurant = restaurantRepository.findById(request.getRestaurantId())
                .orElseThrow(() -> new ResourceNotFoundException("Restaurant not found"));

        if (!restaurant.getIsOpen() || !restaurant.getIsActive()) {
            throw new BusinessException("Restaurant is currently closed");
        }

        // Get delivery address
        Address deliveryAddress = addressRepository.findById(request.getDeliveryAddressId())
                .orElseThrow(() -> new ResourceNotFoundException("Address not found"));

        if (!deliveryAddress.getCustomer().getId().equals(customer.getId())) {
            throw new UnauthorizedException("Invalid delivery address");
        }

        // Create order
        Order order = new Order();
        order.setOrderNumber(generateOrderNumber());
        order.setCustomer(customer);
        order.setRestaurant(restaurant);
        order.setDeliveryAddress(deliveryAddress);
        order.setStatus(Order.OrderStatus.PLACED);
        order.setSpecialInstructions(request.getSpecialInstructions());
        order.setContactlessDelivery(request.getContactlessDelivery());

        // Add order items
        List<OrderItem> orderItems = new ArrayList<>();
        double subtotal = 0.0;

        for (OrderItemRequest itemRequest : request.getItems()) {
            MenuItem menuItem = menuItemRepository.findById(itemRequest.getMenuItemId())
                    .orElseThrow(() -> new ResourceNotFoundException("Menu item not found"));

            if (!menuItem.getAvailable()) {
                throw new BusinessException("Menu item is not available: " + menuItem.getName());
            }

            OrderItem orderItem = new OrderItem();
            orderItem.setOrder(order);
            orderItem.setMenuItem(menuItem);
            orderItem.setQuantity(itemRequest.getQuantity());
            orderItem.setPrice(menuItem.getPrice());
            orderItem.setSpecialInstructions(itemRequest.getSpecialInstructions());

            double itemTotal = menuItem.getPrice() * itemRequest.getQuantity();

            // Handle customizations
            if (itemRequest.getCustomizationChoiceIds() != null) {
                for (Long choiceId : itemRequest.getCustomizationChoiceIds()) {
                    // Add customization logic here
                }
            }

            orderItems.add(orderItem);
            subtotal += itemTotal;
        }

        order.setOrderItems(orderItems);
        order.setSubtotal(subtotal);

        // Check minimum order amount
        if (restaurant.getMinimumOrderAmount() != null && subtotal < restaurant.getMinimumOrderAmount()) {
            throw new BusinessException("Minimum order amount is ₹" + restaurant.getMinimumOrderAmount());
        }

        // Calculate delivery fee
        double deliveryFee = restaurant.getDeliveryFee() != null ? restaurant.getDeliveryFee() : 0.0;
        if (restaurant.getFreeDeliveryAvailable() && restaurant.getFreeDeliveryAbove() != null
                && subtotal >= restaurant.getFreeDeliveryAbove()) {
            deliveryFee = 0.0;
        }
        order.setDeliveryFee(deliveryFee);

        // Calculate tax
        double taxAmount = (subtotal + deliveryFee) * TAX_RATE;
        order.setTaxAmount(taxAmount);

        // Apply coupon if provided
        double discountAmount = 0.0;
        if (request.getCouponCode() != null && !request.getCouponCode().isEmpty()) {
            Coupon coupon = couponService.validateCoupon(request.getCouponCode(), subtotal, restaurant.getId());
            discountAmount = couponService.calculateDiscount(coupon, subtotal);
            order.setAppliedCoupon(coupon);
        }
        order.setDiscountAmount(discountAmount);

        // Calculate total
        double totalAmount = subtotal + deliveryFee + taxAmount - discountAmount;
        order.setTotalAmount(totalAmount);

        // Set estimated delivery time
        order.setEstimatedDeliveryTime(restaurant.getAverageDeliveryTime());
        order.setEstimatedDeliveryAt(LocalDateTime.now().plusMinutes(restaurant.getAverageDeliveryTime()));

        // Save order
        order = orderRepository.save(order);

        // Create payment
        Payment payment = paymentService.createPayment(order.getId(), request.getPaymentMethod());

        // Process payment
        if (!"CASH_ON_DELIVERY".equals(request.getPaymentMethod())) {
            payment = paymentService.processPayment(payment.getId());
            if (payment.getStatus() != Payment.PaymentStatus.COMPLETED) {
                throw new BusinessException("Payment failed");
            }
        }

        // Clear cart
        cartRepository.findByCustomerId(customer.getId())
                .ifPresent(cart -> {
                    cart.getCartItems().clear();
                    cart.setRestaurant(null);
                    cartRepository.save(cart);
                });

        // Add loyalty points
        int loyaltyPoints = (int) (totalAmount / 100); // 1 point per ₹100
        customer.setLoyaltyPoints(customer.getLoyaltyPoints() + loyaltyPoints);
        customerRepository.save(customer);

        return mapToOrderResponse(order);
    }

    @Override
    public OrderResponse getOrderById(Long id) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));

        // Check authorization
        Long currentUserId = securityUtils.getCurrentUserId();
        if (!order.getCustomer().getId().equals(currentUserId)
                && !order.getRestaurant().getOwner().getId().equals(currentUserId)
                && (order.getDeliveryAgent() == null || !order.getDeliveryAgent().getId().equals(currentUserId))) {
            throw new UnauthorizedException("You are not authorized to view this order");
        }

        return mapToOrderResponse(order);
    }

    @Override
    public OrderResponse getOrderByNumber(String orderNumber) {
        Order order = orderRepository.findByOrderNumber(orderNumber)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
        return mapToOrderResponse(order);
    }

    @Override
    public List<OrderResponse> getCustomerOrders() {
        Long customerId = securityUtils.getCurrentUserId();
        return orderRepository.findByCustomerIdOrderByCreatedAtDesc(customerId).stream()
                .map(this::mapToOrderResponse)
                .collect(Collectors.toList());
    }

    @Override
    public List<OrderResponse> getRestaurantOrders(Long restaurantId) {
        // Verify restaurant ownership
        Restaurant restaurant = restaurantRepository.findById(restaurantId)
                .orElseThrow(() -> new ResourceNotFoundException("Restaurant not found"));

        if (!restaurant.getOwner().getId().equals(securityUtils.getCurrentUserId())) {
            throw new UnauthorizedException("You can only view orders for your own restaurant");
        }

        return orderRepository.findByRestaurantIdOrderByCreatedAtDesc(restaurantId).stream()
                .map(this::mapToOrderResponse)
                .collect(Collectors.toList());
    }

    @Override
    public List<OrderResponse> getDeliveryAgentOrders(Long agentId) {
        if (!securityUtils.isCurrentUser(agentId)) {
            throw new UnauthorizedException("You can only view your own deliveries");
        }

        return orderRepository.findByDeliveryAgentIdOrderByCreatedAtDesc(agentId).stream()
                .map(this::mapToOrderResponse)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public OrderResponse updateOrderStatus(Long orderId, Order.OrderStatus status) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));

        order.setStatus(status);

        if (status == Order.OrderStatus.DELIVERED) {
            order.setDeliveredAt(LocalDateTime.now());
        }

        order = orderRepository.save(order);
        return mapToOrderResponse(order);
    }

    @Override
    @Transactional
    public OrderResponse confirmOrder(Long orderId) {
        return updateOrderStatus(orderId, Order.OrderStatus.CONFIRMED);
    }

    @Override
    @Transactional
    public OrderResponse cancelOrder(Long orderId, String reason) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));

        // Only allow cancellation if order is not out for delivery or delivered
        if (order.getStatus() == Order.OrderStatus.OUT_FOR_DELIVERY
                || order.getStatus() == Order.OrderStatus.DELIVERED) {
            throw new BusinessException("Cannot cancel order at this stage");
        }

        order.setStatus(Order.OrderStatus.CANCELLED);
        order = orderRepository.save(order);

        // Process refund if payment was made
        Payment payment = paymentService.getPaymentByOrderId(orderId);
        if (payment != null && payment.getStatus() == Payment.PaymentStatus.COMPLETED) {
            paymentService.refundPayment(payment.getId());
        }

        return mapToOrderResponse(order);
    }

    @Override
    public List<OrderResponse> getPendingOrdersForRestaurant(Long restaurantId) {
        return orderRepository.findByRestaurantAndStatus(restaurantId, Order.OrderStatus.PLACED).stream()
                .map(this::mapToOrderResponse)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public OrderResponse acceptOrder(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));

        if (!order.getRestaurant().getOwner().getId().equals(securityUtils.getCurrentUserId())) {
            throw new UnauthorizedException("You can only accept orders for your own restaurant");
        }

        order.setStatus(Order.OrderStatus.CONFIRMED);
        order = orderRepository.save(order);

        return mapToOrderResponse(order);
    }

    @Override
    @Transactional
    public OrderResponse markOrderReady(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));

        if (!order.getRestaurant().getOwner().getId().equals(securityUtils.getCurrentUserId())) {
            throw new UnauthorizedException("Unauthorized");
        }

        order.setStatus(Order.OrderStatus.READY_FOR_PICKUP);
        order = orderRepository.save(order);

        return mapToOrderResponse(order);
    }

    @Override
    @Transactional
    public OrderResponse assignDeliveryAgent(Long orderId, Long agentId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));

        // This would typically be called by admin or automated system
        // For now, allowing restaurant owner to assign

        order.setStatus(Order.OrderStatus.OUT_FOR_DELIVERY);
        order = orderRepository.save(order);

        return mapToOrderResponse(order);
    }

    @Override
    @Transactional
    public OrderResponse updateDeliveryStatus(Long orderId, Order.OrderStatus status) {
        return updateOrderStatus(orderId, status);
    }

    @Override
    @Transactional
    public OrderResponse markOrderDelivered(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));

        if (order.getDeliveryAgent() != null
                && !order.getDeliveryAgent().getId().equals(securityUtils.getCurrentUserId())) {
            throw new UnauthorizedException("Only assigned delivery agent can mark as delivered");
        }

        order.setStatus(Order.OrderStatus.DELIVERED);
        order.setDeliveredAt(LocalDateTime.now());
        order = orderRepository.save(order);

        return mapToOrderResponse(order);
    }

    @Override
    public OrderResponse trackOrder(Long orderId) {
        return getOrderById(orderId);
    }

    private String generateOrderNumber() {
        return "ORD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private OrderResponse mapToOrderResponse(Order order) {
        return OrderResponse.builder()
                .id(order.getId())
                .orderNumber(order.getOrderNumber())
                .customerId(order.getCustomer().getId())
                .customerName(order.getCustomer().getFullName())
                .restaurantId(order.getRestaurant().getId())
                .restaurantName(order.getRestaurant().getName())
                .items(order.getOrderItems().stream()
                        .map(this::mapToOrderItemResponse)
                        .collect(Collectors.toList()))
                .deliveryAddress(mapToAddressResponse(order.getDeliveryAddress()))
                .status(order.getStatus().name())
                .subtotal(order.getSubtotal())
                .deliveryFee(order.getDeliveryFee())
                .taxAmount(order.getTaxAmount())
                .discountAmount(order.getDiscountAmount())
                .totalAmount(order.getTotalAmount())
                .paymentMethod(order.getPayment() != null ? order.getPayment().getPaymentMethod().name() : null)
                .paymentStatus(order.getPayment() != null ? order.getPayment().getStatus().name() : null)
                .specialInstructions(order.getSpecialInstructions())
                .contactlessDelivery(order.getContactlessDelivery())
                .estimatedDeliveryTime(order.getEstimatedDeliveryTime())
                .estimatedDeliveryAt(order.getEstimatedDeliveryAt())
                .deliveredAt(order.getDeliveredAt())
                .createdAt(order.getCreatedAt())
                .deliveryAgent(order.getDeliveryAgent() != null ? mapToDeliveryAgentResponse(order.getDeliveryAgent()) : null)
                .build();
    }

    private OrderItemResponse mapToOrderItemResponse(OrderItem orderItem) {
        return OrderItemResponse.builder()
                .id(orderItem.getId())
                .menuItemName(orderItem.getMenuItem().getName())
                .quantity(orderItem.getQuantity())
                .price(orderItem.getPrice())
                .customizations(orderItem.getCustomizations().stream()
                        .map(c -> c.getCustomizationChoice().getName())
                        .collect(Collectors.toList()))
                .specialInstructions(orderItem.getSpecialInstructions())
                .build();
    }

    private AddressResponse mapToAddressResponse(Address address) {
        return AddressResponse.builder()
                .id(address.getId())
                .addressLine1(address.getAddressLine1())
                .addressLine2(address.getAddressLine2())
                .city(address.getCity())
                .state(address.getState())
                .pincode(address.getPincode())
                .landmark(address.getLandmark())
                .type(address.getType() != null ? address.getType().name() : null)
                .label(address.getLabel())
                .latitude(address.getLatitude())
                .longitude(address.getLongitude())
                .isDefault(address.getIsDefault())
                .build();
    }

    private DeliveryAgentResponse mapToDeliveryAgentResponse(DeliveryAgent agent) {
        return DeliveryAgentResponse.builder()
                .id(agent.getId())
                .fullName(agent.getFullName())
                .phoneNumber(agent.getPhoneNumber())
                .vehicleType(agent.getVehicleType())
                .vehicleNumber(agent.getVehicleNumber())
                .available(agent.getAvailable())
                .averageRating(agent.getAverageRating())
                .totalDeliveries(agent.getTotalDeliveries())
                .currentLatitude(agent.getCurrentLatitude())
                .currentLongitude(agent.getCurrentLongitude())
                .build();
    }
}
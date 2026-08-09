package com.bhukkad.serviceImpl;

import com.bhukkad.dto.request.OrderRequest;
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
    private final SecurityUtils securityUtils;
    private final CouponService couponService;
    private final PaymentService paymentService;

    @Override
    @Transactional
    public OrderResponse createOrder(OrderRequest request) {
        Customer customer = customerRepository.findById(securityUtils.getCurrentUserId())
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found"));

        Restaurant restaurant = restaurantRepository.findById(request.getRestaurantId())
                .orElseThrow(() -> new ResourceNotFoundException("Restaurant not found"));

        Address address = addressRepository.findById(request.getDeliveryAddressId())
                .orElseThrow(() -> new ResourceNotFoundException("Address not found"));

        Order order = new Order();
        order.setOrderNumber("ORD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        order.setCustomer(customer);
        order.setRestaurant(restaurant);
        order.setDeliveryAddress(address);
        order.setStatus(Order.OrderStatus.PLACED);

        // Items & Totals logic here...
        order.setSubtotal(0.0); // Placeholder
        order.setTotalAmount(0.0); // Placeholder

        order = orderRepository.save(order);
        return mapToOrderResponse(order);
    }

    @Override
    @Transactional(readOnly = true)
    public OrderResponse getOrderById(Long id) {
        Order order = orderRepository.findByIdWithDetails(id)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
        return mapToOrderResponse(order);
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrderResponse> getCustomerOrders() {
        Long customerId = securityUtils.getCurrentUserId();
        return orderRepository.findByCustomerIdWithDetails(customerId).stream()
                .map(this::mapToOrderResponse)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrderResponse> getRestaurantOrders(Long restaurantId) {
        return orderRepository.findByRestaurantIdWithDetails(restaurantId).stream()
                .map(this::mapToOrderResponse)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrderResponse> getPendingOrdersForRestaurant(Long restaurantId) {
        return orderRepository.findByRestaurantAndStatusWithDetails(restaurantId, Order.OrderStatus.PLACED).stream()
                .map(this::mapToOrderResponse)
                .collect(Collectors.toList());
    }

    // Helper mapToOrderResponse must handle lazy loading safely
    private OrderResponse mapToOrderResponse(Order order) {
        // We use safe getters because findByIdWithDetails ensures they are loaded
        return OrderResponse.builder()
                .id(order.getId())
                .orderNumber(order.getOrderNumber())
                .customerName(order.getCustomer().getFullName()) // Triggers Proxy if not fetched
                .restaurantName(order.getRestaurant().getName()) // Triggers Proxy if not fetched
                .status(order.getStatus().name())
                .subtotal(order.getSubtotal())
                .deliveryFee(order.getDeliveryFee())
                .taxAmount(order.getTaxAmount())
                .discountAmount(order.getDiscountAmount())
                .totalAmount(order.getTotalAmount())
                .createdAt(order.getCreatedAt())
                .specialInstructions(order.getSpecialInstructions())
                .build();
    }

    private AddressResponse mapAddress(Address address) {
        return AddressResponse.builder()
                .addressLine1(address.getAddressLine1())
                .city(address.getCity())
                .build();
    }

    // Add other missing interface methods...
    @Override public OrderResponse getOrderByNumber(String n) { return null; }
    @Override public List<OrderResponse> getDeliveryAgentOrders(Long a) { return null; }
    @Override public OrderResponse updateOrderStatus(Long i, Order.OrderStatus s) { return null; }
    @Override public OrderResponse confirmOrder(Long i) { return null; }

    @Override
    @Transactional
    public OrderResponse cancelOrder(Long orderId, String reason) {
        Order order = orderRepository.findByIdWithDetails(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));

        // Add business logic checks (e.g., can't cancel if already out for delivery)

        order.setStatus(Order.OrderStatus.CANCELLED);
        order = orderRepository.save(order);

        return mapToOrderResponse(order);
    }

    @Override
    @Transactional
    public OrderResponse acceptOrder(Long orderId) {
        // Use the query that fetches Customer and Restaurant eagerly
        Order order = orderRepository.findByIdWithDetails(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));

        // Verify ownership (optional but recommended)

        order.setStatus(Order.OrderStatus.CONFIRMED);
        order = orderRepository.save(order);

        // Mapping happens HERE while session is open
        return mapToOrderResponse(order);
    }

    @Override
    @Transactional
    public OrderResponse markOrderReady(Long orderId) {
        Order order = orderRepository.findByIdWithDetails(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));

        order.setStatus(Order.OrderStatus.READY_FOR_PICKUP);
        order = orderRepository.save(order);

        return mapToOrderResponse(order);
    }


    @Override public OrderResponse assignDeliveryAgent(Long i, Long a) { return null; }
    @Override public OrderResponse updateDeliveryStatus(Long i, Order.OrderStatus s) { return null; }
    @Override public OrderResponse markOrderDelivered(Long i) { return null; }
    @Override public OrderResponse trackOrder(Long i) { return getOrderById(i); }


}
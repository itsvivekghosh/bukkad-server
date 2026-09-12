package com.bhukkad.order.domain.service.impl;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.common.util.Constants;
import com.bhukkad.common.util.PriceCalculator;
import com.bhukkad.order.config.SubscriptionProperties;
import com.bhukkad.order.api.dto.request.SubscriptionPlanRequest;
import com.bhukkad.order.api.dto.response.SubscriptionPlanResponse;
import com.bhukkad.order.infrastructure.client.RestaurantClient;
import com.bhukkad.order.infrastructure.client.MenuItemDto;
import com.bhukkad.order.infrastructure.client.MenuSnapshot;
import com.bhukkad.order.domain.entity.Order;
import com.bhukkad.order.domain.entity.OrderItem;
import com.bhukkad.order.domain.entity.SubscriptionDelivery;
import com.bhukkad.order.domain.entity.SubscriptionPlan;
import com.bhukkad.order.domain.repository.OrderItemRepository;
import com.bhukkad.order.domain.repository.OrderRepository;
import com.bhukkad.order.domain.repository.SubscriptionDeliveryRepository;
import com.bhukkad.order.domain.repository.SubscriptionPlanRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import com.bhukkad.order.config.SubscriptionProperties;
import com.bhukkad.order.domain.entity.Subscription;
import com.bhukkad.order.infrastructure.client.MenuItemDto;
import com.bhukkad.order.infrastructure.client.MenuSnapshot;

/**
 * Business logic for recurring weekly subscription meal plans.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionService {

    private final SubscriptionPlanRepository planRepository;
    private final SubscriptionDeliveryRepository deliveryRepository;
    private final OrderRepository orderRepository;
    private final SubscriptionProperties subscriptionProperties;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final RestaurantClient restaurantClient;
    private final OrderItemRepository orderItemRepository;

    @Transactional
    public SubscriptionPlanResponse createPlan(Long userId, SubscriptionPlanRequest request) {
        SubscriptionPlan.Weekday weekday;
        try {
            weekday = request.weekdayEnum();
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ex.getMessage());
        }
        if (request.deliveryTime() == null) {
            throw new BusinessException("Delivery time is required");
        }

        SubscriptionPlan plan = new SubscriptionPlan();
        plan.setUserId(userId);
        plan.setRestaurantId(request.restaurantId());
        plan.setTitle(request.title());
        plan.setItemsJson(toItemsJson(request.items()));
        plan.setWeekday(weekday);
        plan.setDeliveryTime(request.deliveryTime());
        plan.setDeliveryAddressId(request.deliveryAddressId());
        plan.setPaymentMethod(normalizePaymentMethod(request.paymentMethod()));
        plan.setStatus(SubscriptionPlan.SubscriptionStatus.ACTIVE);
        plan.setStartDate(request.startDate());
        plan.setNextDeliveryDate(computeNextDeliveryDate(weekday, request.startDate()));
        plan.setCreatedAt(LocalDateTime.now());
        plan = planRepository.save(plan);

        log.info("Subscription plan created | planId={} | userId={} | restaurantId={} | weekday={} | nextDelivery={}",
                plan.getId(), userId, plan.getRestaurantId(), weekday, plan.getNextDeliveryDate());
        return SubscriptionPlanResponse.from(plan);
    }

    @Transactional
    public SubscriptionPlanResponse pausePlan(Long planId, Long userId) {
        SubscriptionPlan plan = getOwnedPlan(planId, userId);
        if (plan.getStatus() == SubscriptionPlan.SubscriptionStatus.CANCELLED) {
            throw new BusinessException("Subscription plan is already cancelled");
        }
        if (plan.getStatus() == SubscriptionPlan.SubscriptionStatus.ACTIVE) {
            plan.setStatus(SubscriptionPlan.SubscriptionStatus.PAUSED);
            planRepository.save(plan);
            log.info("Subscription plan paused | planId={}", planId);
        }
        return SubscriptionPlanResponse.from(plan);
    }

    @Transactional
    public SubscriptionPlanResponse resumePlan(Long planId, Long userId) {
        SubscriptionPlan plan = getOwnedPlan(planId, userId);
        if (plan.getStatus() == SubscriptionPlan.SubscriptionStatus.CANCELLED) {
            throw new BusinessException("Subscription plan is already cancelled");
        }
        if (plan.getStatus() == SubscriptionPlan.SubscriptionStatus.PAUSED) {
            plan.setStatus(SubscriptionPlan.SubscriptionStatus.ACTIVE);
            if (plan.getNextDeliveryDate() == null) {
                plan.setNextDeliveryDate(computeNextDeliveryDate(plan.getWeekday(), plan.getStartDate()));
            }
            planRepository.save(plan);
            log.info("Subscription plan resumed | planId={}", planId);
        }
        return SubscriptionPlanResponse.from(plan);
    }

    @Transactional
    public SubscriptionPlanResponse cancelPlan(Long planId, Long userId) {
        SubscriptionPlan plan = getOwnedPlan(planId, userId);
        if (plan.getStatus() != SubscriptionPlan.SubscriptionStatus.CANCELLED) {
            plan.setStatus(SubscriptionPlan.SubscriptionStatus.CANCELLED);
            plan.setNextDeliveryDate(null);
            planRepository.save(plan);
            log.info("Subscription plan cancelled | planId={}", planId);
        }
        return SubscriptionPlanResponse.from(plan);
    }

    @Transactional
    public SubscriptionPlanResponse skipNextDelivery(Long planId, Long userId) {
        SubscriptionPlan plan = getOwnedPlan(planId, userId);
        if (plan.getStatus() != SubscriptionPlan.SubscriptionStatus.ACTIVE) {
            throw new BusinessException("Only active subscription plans can be skipped");
        }
        LocalDate dueDate = plan.getNextDeliveryDate();
        if (dueDate == null) {
            throw new BusinessException("Subscription plan has no upcoming delivery");
        }

        SubscriptionDelivery delivery = deliveryRepository.findByPlanIdAndScheduledDate(planId, dueDate)
                .orElseGet(() -> {
                    SubscriptionDelivery d = new SubscriptionDelivery();
                    d.setPlan(plan);
                    d.setScheduledDate(dueDate);
                    return d;
                });
        delivery.setStatus(SubscriptionDelivery.DeliveryStatus.SKIPPED);
        deliveryRepository.save(delivery);
        plan.getDeliveries().add(delivery);

        plan.setNextDeliveryDate(dueDate.plusDays(7));
        planRepository.save(plan);
        log.info("Subscription delivery skipped | planId={} | date={}", planId, dueDate);
        return SubscriptionPlanResponse.from(plan);
    }

    @Transactional(readOnly = true)
    public List<SubscriptionPlanResponse> listPlans(Long userId) {
        return planRepository.findByUserId(userId).stream()
                .map(SubscriptionPlanResponse::from)
                .toList();
    }

    public int materializeDue() {
        if (!subscriptionProperties.isEnabled()) {
            log.debug("Subscription materialization disabled; skipping");
            return 0;
        }
        LocalDate today = LocalDate.now();
        List<SubscriptionPlan> duePlans = planRepository.findActivePlansDueOnOrBefore(today);
        if (duePlans.isEmpty()) {
            return 0;
        }
        log.info("Materializing {} due subscription plans", duePlans.size());

        int processed = 0;
        for (SubscriptionPlan plan : duePlans) {
            try {
                boolean placed = transactionTemplate.execute(status -> materializePlan(plan.getId()));
                if (placed) {
                    processed++;
                }
            } catch (Exception ex) {
                log.error("Failed to materialize subscription plan {}: {}", plan.getId(), ex.getMessage(), ex);
            }
        }
        return processed;
    }

    // ==================== HELPERS ====================

    private boolean materializePlan(Long planId) {
        SubscriptionPlan plan = planRepository.findById(planId)
                .orElseThrow(() -> new ResourceNotFoundException("Subscription plan not found"));
        if (plan.getStatus() != SubscriptionPlan.SubscriptionStatus.ACTIVE) {
            return false;
        }
        LocalDate dueDate = plan.getNextDeliveryDate();
        if (dueDate == null) {
            return false;
        }

        SubscriptionDelivery delivery = deliveryRepository.findByPlanIdAndScheduledDate(planId, dueDate)
                .orElseGet(() -> {
                    SubscriptionDelivery d = new SubscriptionDelivery();
                    d.setPlan(plan);
                    d.setScheduledDate(dueDate);
                    d.setStatus(SubscriptionDelivery.DeliveryStatus.PENDING);
                    return deliveryRepository.save(d);
                });

        if (delivery.getStatus() == SubscriptionDelivery.DeliveryStatus.SKIPPED) {
            advancePlan(plan);
            return false;
        }
        if (delivery.getStatus() == SubscriptionDelivery.DeliveryStatus.PLACED) {
            advancePlan(plan);
            return false;
        }

        try {
            Order order = buildOrder(plan, dueDate);
            order = orderRepository.save(order);
            delivery.setStatus(SubscriptionDelivery.DeliveryStatus.PLACED);
            delivery.setOrderId(order.getId());
            deliveryRepository.save(delivery);
            advancePlan(plan);
            log.info("Subscription delivery materialized | planId={} | date={} | orderId={} | orderNumber={}",
                    planId, dueDate, order.getId(), order.getOrderNumber());
            return true;
        } catch (Exception ex) {
            log.error("Subscription materialization failed | planId={} | date={} | error={}",
                    planId, dueDate, ex.getMessage(), ex);
            delivery.setStatus(SubscriptionDelivery.DeliveryStatus.FAILED);
            deliveryRepository.save(delivery);
            advancePlan(plan);
            return false;
        }
    }

    private Order buildOrder(SubscriptionPlan plan, LocalDate dueDate) {
        List<SubscriptionPlanRequest.Item> snapshots = parseItems(plan.getItemsJson());

        // Fetch live menu prices from the restaurant service
        MenuSnapshot menuSnapshot = restaurantClient.getMenu(plan.getRestaurantId())
                .switchIfEmpty(reactor.core.publisher.Mono.error(new BusinessException("Restaurant menu not found")))
                .block();

        List<MenuItemDto> menuItems = menuSnapshot != null ? menuSnapshot.getItems() : List.of();
        List<Long> menuItemIds = snapshots.stream()
                .map(SubscriptionPlanRequest.Item::menuItemId)
                .toList();
        List<MenuItemDto> matchedItems = menuItems.stream()
                .filter(m -> menuItemIds.contains(m.getId()))
                .toList();

        List<OrderItem> orderItems = new ArrayList<>();
        double subtotal = 0.0;
        for (SubscriptionPlanRequest.Item snapshot : snapshots) {
            MenuItemDto menuItem = matchedItems.stream()
                    .filter(m -> m.getId().equals(snapshot.menuItemId()))
                    .findFirst()
                    .orElseThrow(() -> new BusinessException("Menu item not found: " + snapshot.menuItemId()));
            double itemPrice = menuItem.getPrice() != null ? menuItem.getPrice().doubleValue() : 0.0;
            subtotal += PriceCalculator.calculateSubtotal(itemPrice, snapshot.quantity());
            OrderItem orderItem = new OrderItem();
            orderItem.setMenuItemId(menuItem.getId());
            orderItem.setItemName(menuItem.getName());
            orderItem.setUnitPrice(menuItem.getPrice());
            orderItem.setQuantity(snapshot.quantity());
            orderItems.add(orderItem);
        }

        double deliveryFee = Constants.DEFAULT_DELIVERY_FEE;
        double taxAmount = PriceCalculator.calculateTax(subtotal);

        Order order = new Order();
        order.setOrderNumber(generateOrderNumber());
        order.setCustomerId(plan.getUserId());
        order.setRestaurantId(plan.getRestaurantId());
        order.setStatus(Order.STATUS_SCHEDULED);
        order.setScheduledAt(LocalDateTime.of(dueDate, plan.getDeliveryTime()));
        order.setSpecialInstructions(plan.getTitle() != null ? "Subscription: " + plan.getTitle() : "Weekly subscription delivery");
        order.setSubtotal(PriceCalculator.roundToTwoDecimals(subtotal));
        order.setDeliveryFee(PriceCalculator.roundToTwoDecimals(deliveryFee));
        order.setTaxAmount(PriceCalculator.roundToTwoDecimals(taxAmount));
        order.setDiscountAmount(0.0);
        order.setTotalAmount(java.math.BigDecimal.valueOf(PriceCalculator.roundToTwoDecimals(subtotal + deliveryFee + taxAmount)));

        int deliveryMinutes = Constants.DEFAULT_DELIVERY_TIME;
        order.setEstimatedDeliveryTime(deliveryMinutes);
        order.setEstimatedDeliveryAt(order.getScheduledAt().plusMinutes(deliveryMinutes));

        order.setDeliveryAddressId(plan.getDeliveryAddressId());

        Order savedOrder = orderRepository.save(order);
        for (OrderItem orderItem : orderItems) {
            orderItem.setOrderId(savedOrder.getId());
            orderItemRepository.save(orderItem);
        }
        return savedOrder;
    }

    private void advancePlan(SubscriptionPlan plan) {
        LocalDate next = plan.getNextDeliveryDate() != null
                ? plan.getNextDeliveryDate().plusDays(7)
                : computeNextDeliveryDate(plan.getWeekday(), plan.getStartDate());
        plan.setNextDeliveryDate(next);
        planRepository.save(plan);
    }

    private SubscriptionPlan getOwnedPlan(Long planId, Long userId) {
        return planRepository.findByIdAndUserId(planId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Subscription plan not found"));
    }

    private LocalDate computeNextDeliveryDate(SubscriptionPlan.Weekday weekday, LocalDate startDate) {
        DayOfWeek target = toDayOfWeek(weekday);
        LocalDate date = startDate;
        while (!date.getDayOfWeek().equals(target)) {
            date = date.plusDays(1);
        }
        LocalDate today = LocalDate.now();
        while (date.isBefore(today)) {
            date = date.plusDays(7);
        }
        return date;
    }

    private DayOfWeek toDayOfWeek(SubscriptionPlan.Weekday weekday) {
        return switch (weekday) {
            case MON -> DayOfWeek.MONDAY;
            case TUE -> DayOfWeek.TUESDAY;
            case WED -> DayOfWeek.WEDNESDAY;
            case THU -> DayOfWeek.THURSDAY;
            case FRI -> DayOfWeek.FRIDAY;
            case SAT -> DayOfWeek.SATURDAY;
            case SUN -> DayOfWeek.SUNDAY;
        };
    }

    private String toItemsJson(List<SubscriptionPlanRequest.Item> items) {
        try {
            return objectMapper.writeValueAsString(items);
        } catch (JsonProcessingException ex) {
            throw new BusinessException("Failed to serialize subscription items", ex);
        }
    }

    private List<SubscriptionPlanRequest.Item> parseItems(String itemsJson) {
        if (!StringUtils.hasText(itemsJson)) {
            throw new BusinessException("Subscription plan has no items");
        }
        try {
            return objectMapper.readValue(itemsJson, new TypeReference<List<SubscriptionPlanRequest.Item>>() {
            });
        } catch (JsonProcessingException ex) {
            throw new BusinessException("Failed to parse subscription items", ex);
        }
    }

    private String normalizePaymentMethod(String paymentMethod) {
        if (paymentMethod == null || paymentMethod.isBlank()) {
            throw new BusinessException("Payment method is required");
        }
        String normalized = paymentMethod.trim().toUpperCase();
        if ("COD".equals(normalized)) {
            return "CASH_ON_DELIVERY";
        }
        return normalized;
    }

    private String generateOrderNumber() {
        return "ORD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}

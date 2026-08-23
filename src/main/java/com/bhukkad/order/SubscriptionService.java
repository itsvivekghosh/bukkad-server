package com.bhukkad.order;

import com.bhukkad.config.SubscriptionProperties;
import com.bhukkad.dto.request.SubscriptionPlanRequest;
import com.bhukkad.dto.response.SubscriptionPlanResponse;
import com.bhukkad.entity.*;
import com.bhukkad.exception.BusinessException;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.repository.*;
import com.bhukkad.util.Constants;
import com.bhukkad.util.PriceCalculator;
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

/**
 * Business logic for recurring weekly subscription meal plans.
 *
 * <p>Plans snapshot their menu items (ids + quantities) at subscribe time as
 * JSON ({@code items_json}) so the scheduler can materialise a due delivery
 * into a real {@link Order} without relying on the customer's current cart.
 * Materialised orders are created with {@link Order.OrderStatus#SCHEDULED} and
 * a {@code scheduledAt} equal to the delivery slot; the existing scheduled-order
 * path ({@link ScheduledOrderScheduler}) then dispatches them when due.</p>
 *
 * <p>Each plan is processed in its own transaction (via
 * {@link TransactionTemplate}, because self-invocation would bypass
 * {@code @Transactional}) so one plan's failure never blocks the others.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionService {

    private final SubscriptionPlanRepository planRepository;
    private final SubscriptionDeliveryRepository deliveryRepository;
    private final AddressRepository addressRepository;
    private final RestaurantRepository restaurantRepository;
    private final MenuItemRepository menuItemRepository;
    private final CustomerRepository customerRepository;
    private final OrderRepository orderRepository;
    private final SubscriptionProperties subscriptionProperties;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    /**
     * Creates a weekly plan for the given customer, validating the restaurant,
     * delivery address ownership and that every menu item belongs to the
     * restaurant. The first delivery is scheduled to the first occurrence of
     * the plan's weekday on or after {@code startDate}.
     */
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

        Restaurant restaurant = restaurantRepository.findById(request.restaurantId())
                .orElseThrow(() -> new ResourceNotFoundException("Restaurant not found"));

        Address address = addressRepository.findByIdWithCustomer(request.deliveryAddressId())
                .orElseThrow(() -> new ResourceNotFoundException("Address not found"));
        if (!address.getCustomer().getId().equals(userId)) {
            throw new BusinessException("Delivery address does not belong to customer");
        }

        List<Long> menuItemIds = request.items().stream()
                .map(SubscriptionPlanRequest.Item::menuItemId)
                .toList();
        List<MenuItem> menuItems = menuItemRepository.findAllById(menuItemIds);
        if (menuItems.size() != menuItemIds.size()) {
            throw new BusinessException("One or more menu items were not found");
        }
        for (MenuItem menuItem : menuItems) {
            if (!menuItem.getCategory().getRestaurant().getId().equals(request.restaurantId())) {
                throw new BusinessException("Menu item " + menuItem.getId()
                        + " does not belong to the selected restaurant");
            }
        }

        SubscriptionPlan plan = new SubscriptionPlan();
        plan.setUserId(userId);
        plan.setRestaurantId(restaurant.getId());
        plan.setTitle(request.title());
        plan.setItemsJson(toItemsJson(request.items()));
        plan.setWeekday(weekday);
        plan.setDeliveryTime(request.deliveryTime());
        plan.setDeliveryAddressId(address.getId());
        plan.setPaymentMethod(normalizePaymentMethod(request.paymentMethod()));
        plan.setStatus(SubscriptionPlan.SubscriptionStatus.ACTIVE);
        plan.setStartDate(request.startDate());
        plan.setNextDeliveryDate(computeNextDeliveryDate(weekday, request.startDate()));
        plan.setCreatedAt(LocalDateTime.now());
        plan = planRepository.save(plan);

        log.info("Subscription plan created | planId={} | userId={} | restaurantId={} | weekday={} | nextDelivery={}",
                plan.getId(), userId, restaurant.getId(), weekday, plan.getNextDeliveryDate());
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

    /**
     * Skips the next due delivery by writing a SKIPPED delivery row for the
     * current {@code nextDeliveryDate}, then advances the plan by one week.
     */
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

    /**
     * Scheduler entry point: materialises every ACTIVE plan whose
     * {@code nextDeliveryDate} is today or earlier into a real SCHEDULED order.
     * Each plan runs in its own transaction; a failure in one plan is logged
     * and the remaining plans are still processed.
     *
     * @return number of plans successfully materialised
     */
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

    /**
     * @return {@code true} when the due delivery was materialised into an order,
     * {@code false} when it was skipped or failed (the FAILED state is persisted
     * and the plan advanced so the failure never blocks other plans)
     */
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
        Customer customer = customerRepository.findById(plan.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found"));
        Restaurant restaurant = restaurantRepository.findById(plan.getRestaurantId())
                .orElseThrow(() -> new ResourceNotFoundException("Restaurant not found"));
        Address address = addressRepository.findByIdWithCustomer(plan.getDeliveryAddressId())
                .orElseThrow(() -> new ResourceNotFoundException("Address not found"));

        List<SubscriptionPlanRequest.Item> snapshots = parseItems(plan.getItemsJson());
        List<Long> menuItemIds = snapshots.stream()
                .map(SubscriptionPlanRequest.Item::menuItemId)
                .toList();
        List<MenuItem> menuItems = menuItemRepository.findAllById(menuItemIds);

        List<OrderItem> orderItems = new ArrayList<>();
        double subtotal = 0.0;
        for (SubscriptionPlanRequest.Item snapshot : snapshots) {
            MenuItem menuItem = menuItems.stream()
                    .filter(m -> m.getId().equals(snapshot.menuItemId()))
                    .findFirst()
                    .orElseThrow(() -> new BusinessException("Menu item not found: " + snapshot.menuItemId()));
            subtotal += PriceCalculator.calculateSubtotal(menuItem.getPrice(), snapshot.quantity());
            OrderItem orderItem = new OrderItem();
            orderItem.setMenuItem(menuItem);
            orderItem.setQuantity(snapshot.quantity());
            orderItem.setPrice(menuItem.getPrice());
            orderItems.add(orderItem);
        }

        double deliveryFee = restaurant.getDeliveryFee() != null ? restaurant.getDeliveryFee() : 0.0;
        double taxAmount = PriceCalculator.calculateTax(subtotal);

        Order order = new Order();
        order.setOrderNumber(generateOrderNumber());
        order.setCustomer(customer);
        order.setRestaurant(restaurant);
        order.setDeliveryAddress(address);
        order.setStatus(Order.OrderStatus.SCHEDULED);
        order.setScheduledAt(LocalDateTime.of(dueDate, plan.getDeliveryTime()));
        order.setSpecialInstructions(plan.getTitle() != null ? "Subscription: " + plan.getTitle() : "Weekly subscription delivery");
        order.setSubtotal(PriceCalculator.roundToTwoDecimals(subtotal));
        order.setDeliveryFee(PriceCalculator.roundToTwoDecimals(deliveryFee));
        order.setTaxAmount(PriceCalculator.roundToTwoDecimals(taxAmount));
        order.setDiscountAmount(0.0);
        order.setTotalAmount(PriceCalculator.roundToTwoDecimals(subtotal + deliveryFee + taxAmount));

        int deliveryMinutes = restaurant.getAverageDeliveryTime() != null
                ? restaurant.getAverageDeliveryTime()
                : Constants.DEFAULT_DELIVERY_TIME;
        order.setEstimatedDeliveryTime(deliveryMinutes);
        order.setEstimatedDeliveryAt(order.getScheduledAt().plusMinutes(deliveryMinutes));

        orderItems.forEach(orderItem -> orderItem.setOrder(order));
        order.setOrderItems(orderItems);
        return order;
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
        while (date.getDayOfWeek() != target) {
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
            return Payment.PaymentMethod.CASH_ON_DELIVERY.name();
        }
        try {
            Payment.PaymentMethod.valueOf(normalized);
            return normalized;
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("Invalid payment method: " + paymentMethod);
        }
    }

    private String generateOrderNumber() {
        return "ORD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}

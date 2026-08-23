package com.bhukkad.order;

import com.bhukkad.config.SubscriptionProperties;
import com.bhukkad.dto.request.SubscriptionPlanRequest;
import com.bhukkad.dto.response.SubscriptionPlanResponse;
import com.bhukkad.entity.*;
import com.bhukkad.exception.BusinessException;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SubscriptionServiceTest {

    @Mock
    private SubscriptionPlanRepository planRepository;
    @Mock
    private SubscriptionDeliveryRepository deliveryRepository;
    @Mock
    private AddressRepository addressRepository;
    @Mock
    private RestaurantRepository restaurantRepository;
    @Mock
    private MenuItemRepository menuItemRepository;
    @Mock
    private CustomerRepository customerRepository;
    @Mock
    private OrderRepository orderRepository;
    @Mock
    private TransactionTemplate transactionTemplate;

    private SubscriptionProperties properties;
    private SubscriptionService service;

    @BeforeEach
    void setUp() {
        properties = new SubscriptionProperties();
        service = new SubscriptionService(planRepository, deliveryRepository, addressRepository,
                restaurantRepository, menuItemRepository, customerRepository, orderRepository,
                properties, new ObjectMapper(), transactionTemplate);
    }

    // ============================ createPlan ============================

    @Test
    void createPlan_validatesAddressOwnership() {
        SubscriptionPlanRequest request = request();
        Address address = addressWithCustomer(5L);
        when(addressRepository.findByIdWithCustomer(request.deliveryAddressId())).thenReturn(Optional.of(address));
        when(restaurantRepository.findById(1L)).thenReturn(Optional.of(restaurant()));

        assertThrows(BusinessException.class, () -> service.createPlan(9L, request));
        verify(planRepository, never()).save(any());
    }

    @Test
    void createPlan_validatesMenuItemsBelongToRestaurant() {
        SubscriptionPlanRequest request = request();
        when(restaurantRepository.findById(1L)).thenReturn(Optional.of(restaurant()));
        when(addressRepository.findByIdWithCustomer(request.deliveryAddressId()))
                .thenReturn(Optional.of(addressWithCustomer(1L)));

        MenuItem foreign = menuItem(99L, 2L);
        when(menuItemRepository.findAllById(any())).thenReturn(List.of(foreign));

        assertThrows(BusinessException.class, () -> service.createPlan(1L, request));
        verify(planRepository, never()).save(any());
    }

    @Test
    void createPlan_persistsPlanWithComputedNextDeliveryDate() {
        SubscriptionPlanRequest request = new SubscriptionPlanRequest(
                1L, "Weekly Lunch", "WED", LocalTime.of(13, 0), 10L, "COD",
                LocalDate.now(), List.of(new SubscriptionPlanRequest.Item(1L, 2)));
        when(restaurantRepository.findById(1L)).thenReturn(Optional.of(restaurant()));
        when(addressRepository.findByIdWithCustomer(10L)).thenReturn(Optional.of(addressWithCustomer(1L)));
        MenuItem item = menuItem(1L, 1L);
        when(menuItemRepository.findAllById(eq(List.of(1L)))).thenReturn(List.of(item));
        when(planRepository.save(any(SubscriptionPlan.class))).thenAnswer(inv -> {
            SubscriptionPlan saved = inv.getArgument(0);
            saved.setId(101L);
            return saved;
        });

        SubscriptionPlanResponse response = service.createPlan(1L, request);

        ArgumentCaptor<SubscriptionPlan> captor = ArgumentCaptor.forClass(SubscriptionPlan.class);
        verify(planRepository).save(captor.capture());
        SubscriptionPlan saved = captor.getValue();
        assertEquals(1L, saved.getUserId());
        assertEquals(SubscriptionPlan.Weekday.WED, saved.getWeekday());
        assertEquals(SubscriptionPlan.SubscriptionStatus.ACTIVE, saved.getStatus());
        assertEquals("CASH_ON_DELIVERY", saved.getPaymentMethod());
        assertNotNull(saved.getNextDeliveryDate());
        assertNotNull(response.id());
        assertTrue(saved.getItemsJson().contains("\"menuItemId\":1"));
    }

    // ============================ ownership ops ============================

    @Test
    void pausePlan_throwsWhenPlanDoesNotBelongToUser() {
        when(planRepository.findByIdAndUserId(1L, 9L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.pausePlan(1L, 9L));
    }

    @Test
    void cancelPlan_throwsWhenPlanDoesNotBelongToUser() {
        when(planRepository.findByIdAndUserId(1L, 9L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.cancelPlan(1L, 9L));
    }

    @Test
    void skipNextDelivery_throwsWhenPlanDoesNotBelongToUser() {
        when(planRepository.findByIdAndUserId(1L, 9L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.skipNextDelivery(1L, 9L));
    }

    @Test
    void pausePlan_pausesActivePlan() {
        SubscriptionPlan plan = activePlan();
        when(planRepository.findByIdAndUserId(1L, 1L)).thenReturn(Optional.of(plan));

        SubscriptionPlanResponse response = service.pausePlan(1L, 1L);

        assertEquals(SubscriptionPlan.SubscriptionStatus.PAUSED, plan.getStatus());
        assertEquals(SubscriptionPlan.SubscriptionStatus.PAUSED, response.status());
        verify(planRepository).save(plan);
    }

    @Test
    void skipNextDelivery_writesSkippedDeliveryAndAdvancesDate() {
        SubscriptionPlan plan = activePlan();
        plan.setNextDeliveryDate(LocalDate.of(2026, 8, 26));
        when(planRepository.findByIdAndUserId(1L, 1L)).thenReturn(Optional.of(plan));
        when(deliveryRepository.findByPlanIdAndScheduledDate(1L, plan.getNextDeliveryDate()))
                .thenReturn(Optional.empty());
        when(deliveryRepository.save(any(SubscriptionDelivery.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        SubscriptionPlanResponse response = service.skipNextDelivery(1L, 1L);

        ArgumentCaptor<SubscriptionDelivery> captor = ArgumentCaptor.forClass(SubscriptionDelivery.class);
        verify(deliveryRepository).save(captor.capture());
        assertEquals(SubscriptionDelivery.DeliveryStatus.SKIPPED, captor.getValue().getStatus());
        assertEquals(LocalDate.of(2026, 8, 26), captor.getValue().getScheduledDate());
        assertEquals(LocalDate.of(2026, 9, 2), plan.getNextDeliveryDate());
        assertEquals(SubscriptionDelivery.DeliveryStatus.SKIPPED, response.deliveries().get(0).status());
    }

    // ============================ materializeDue ============================

    @Test
    void materializeDue_skipsWhenDisabled() {
        properties.setEnabled(false);

        int processed = service.materializeDue();

        assertEquals(0, processed);
        verify(planRepository, never()).findActivePlansDueOnOrBefore(any());
    }

    @Test
    void materializeDue_placesScheduledOrderAndAdvancesDate() {
        properties.setEnabled(true);
        SubscriptionPlan plan = activePlan();
        plan.setNextDeliveryDate(LocalDate.now());
        plan.setItemsJson("[{\"menuItemId\":1,\"quantity\":2}]");
        when(planRepository.findActivePlansDueOnOrBefore(any(LocalDate.class))).thenReturn(List.of(plan));
        when(transactionTemplate.execute(any(TransactionCallback.class))).thenAnswer(inv -> {
            TransactionCallback<Boolean> callback = inv.getArgument(0);
            return callback.doInTransaction(null);
        });
        when(planRepository.findById(1L)).thenReturn(Optional.of(plan));
        when(deliveryRepository.findByPlanIdAndScheduledDate(1L, plan.getNextDeliveryDate()))
                .thenReturn(Optional.empty());
        when(deliveryRepository.save(any(SubscriptionDelivery.class))).thenAnswer(inv -> inv.getArgument(0));

        Customer customer = new Customer();
        customer.setId(1L);
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer));
        Restaurant restaurant = restaurant();
        when(restaurantRepository.findById(1L)).thenReturn(Optional.of(restaurant));
        when(addressRepository.findByIdWithCustomer(10L)).thenReturn(Optional.of(addressWithCustomer(1L)));
        when(menuItemRepository.findAllById(eq(List.of(1L)))).thenReturn(List.of(menuItem(1L, 1L)));
        Order order = new Order();
        order.setId(50L);
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> {
            Order saved = inv.getArgument(0);
            saved.setId(50L);
            return saved;
        });
        when(planRepository.save(any(SubscriptionPlan.class))).thenAnswer(inv -> inv.getArgument(0));

        int processed = service.materializeDue();

        assertEquals(1, processed);
        ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).save(orderCaptor.capture());
        Order savedOrder = orderCaptor.getValue();
        assertEquals(Order.OrderStatus.SCHEDULED, savedOrder.getStatus());
        assertNotNull(savedOrder.getScheduledAt());
        assertEquals(LocalDate.now(), savedOrder.getScheduledAt().toLocalDate());
        assertEquals(1, savedOrder.getOrderItems().size());
        assertEquals(2, savedOrder.getOrderItems().get(0).getQuantity());
        assertEquals(LocalDate.now().plusDays(7), plan.getNextDeliveryDate());

        ArgumentCaptor<SubscriptionDelivery> deliveryCaptor = ArgumentCaptor.forClass(SubscriptionDelivery.class);
        verify(deliveryRepository, atLeastOnce()).save(deliveryCaptor.capture());
        SubscriptionDelivery saved = deliveryCaptor.getAllValues().stream()
                .filter(d -> d.getStatus() == SubscriptionDelivery.DeliveryStatus.PLACED)
                .findFirst().orElseThrow();
        assertEquals(50L, saved.getOrderId());
    }

    @Test
    void materializeDue_reusesExistingPendingDelivery() {
        properties.setEnabled(true);
        SubscriptionPlan plan = activePlan();
        plan.setNextDeliveryDate(LocalDate.now());
        when(planRepository.findActivePlansDueOnOrBefore(any(LocalDate.class))).thenReturn(List.of(plan));
        when(transactionTemplate.execute(any(TransactionCallback.class))).thenAnswer(inv -> {
            TransactionCallback<Boolean> callback = inv.getArgument(0);
            return callback.doInTransaction(null);
        });
        when(planRepository.findById(1L)).thenReturn(Optional.of(plan));

        SubscriptionDelivery pending = new SubscriptionDelivery();
        pending.setId(7L);
        pending.setPlan(plan);
        pending.setScheduledDate(plan.getNextDeliveryDate());
        pending.setStatus(SubscriptionDelivery.DeliveryStatus.PENDING);
        when(deliveryRepository.findByPlanIdAndScheduledDate(1L, plan.getNextDeliveryDate()))
                .thenReturn(Optional.of(pending));
        when(deliveryRepository.save(any(SubscriptionDelivery.class))).thenAnswer(inv -> inv.getArgument(0));

        Customer customer = new Customer();
        customer.setId(1L);
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer));
        when(restaurantRepository.findById(1L)).thenReturn(Optional.of(restaurant()));
        when(addressRepository.findByIdWithCustomer(10L)).thenReturn(Optional.of(addressWithCustomer(1L)));
        when(menuItemRepository.findAllById(any())).thenReturn(List.of(menuItem(1L, 1L)));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> {
            Order saved = inv.getArgument(0);
            saved.setId(60L);
            return saved;
        });
        when(planRepository.save(any(SubscriptionPlan.class))).thenAnswer(inv -> inv.getArgument(0));

        service.materializeDue();

        assertEquals(SubscriptionDelivery.DeliveryStatus.PLACED, pending.getStatus());
        assertEquals(60L, pending.getOrderId());
        verify(deliveryRepository).save(pending);
    }

    @Test
    void materializeDue_failureInOnePlanDoesNotBlockOthers() {
        properties.setEnabled(true);
        SubscriptionPlan plan1 = activePlan();
        plan1.setId(1L);
        plan1.setNextDeliveryDate(LocalDate.now());
        SubscriptionPlan plan2 = activePlan();
        plan2.setId(2L);
        plan2.setUserId(2L);
        plan2.setNextDeliveryDate(LocalDate.now());

        when(planRepository.findActivePlansDueOnOrBefore(any(LocalDate.class)))
                .thenReturn(List.of(plan1, plan2));
        when(transactionTemplate.execute(any(TransactionCallback.class))).thenAnswer(inv -> {
            TransactionCallback<Boolean> callback = inv.getArgument(0);
            return callback.doInTransaction(null);
        });
        when(planRepository.findById(1L)).thenReturn(Optional.of(plan1));
        when(planRepository.findById(2L)).thenReturn(Optional.of(plan2));
        when(deliveryRepository.findByPlanIdAndScheduledDate(eq(1L), any(LocalDate.class)))
                .thenReturn(Optional.empty());
        when(deliveryRepository.findByPlanIdAndScheduledDate(eq(2L), any(LocalDate.class)))
                .thenReturn(Optional.empty());
        when(deliveryRepository.save(any(SubscriptionDelivery.class))).thenAnswer(inv -> inv.getArgument(0));
        when(customerRepository.findById(1L)).thenThrow(new ResourceNotFoundException("Customer not found"));
        Customer customer = new Customer();
        customer.setId(2L);
        when(customerRepository.findById(2L)).thenReturn(Optional.of(customer));
        when(restaurantRepository.findById(any())).thenReturn(Optional.of(restaurant()));
        when(addressRepository.findByIdWithCustomer(any())).thenReturn(Optional.of(addressWithCustomer(2L)));
        when(menuItemRepository.findAllById(any())).thenReturn(List.of(menuItem(1L, 1L)));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> {
            Order saved = inv.getArgument(0);
            saved.setId(70L);
            return saved;
        });
        when(planRepository.save(any(SubscriptionPlan.class))).thenAnswer(inv -> inv.getArgument(0));

        int processed = service.materializeDue();

        assertEquals(1, processed);
        verify(orderRepository).save(any(Order.class));
    }

    // ============================ fixtures ============================

    private SubscriptionPlanRequest request() {
        return new SubscriptionPlanRequest(
                1L, "Weekly Lunch", "MON", LocalTime.of(13, 0), 10L, "COD",
                LocalDate.now(), List.of(new SubscriptionPlanRequest.Item(1L, 2)));
    }

    private Address addressWithCustomer(Long customerId) {
        Customer customer = new Customer();
        customer.setId(customerId);
        Address address = new Address();
        address.setId(10L);
        address.setCustomer(customer);
        return address;
    }

    private Restaurant restaurant() {
        Restaurant restaurant = new Restaurant();
        restaurant.setId(1L);
        restaurant.setDeliveryFee(40.0);
        restaurant.setAverageDeliveryTime(30);
        restaurant.setIsActive(true);
        return restaurant;
    }

    private MenuItem menuItem(Long id, Long restaurantId) {
        Restaurant restaurant = new Restaurant();
        restaurant.setId(restaurantId);
        MenuCategory category = new MenuCategory();
        category.setRestaurant(restaurant);
        MenuItem item = new MenuItem();
        item.setId(id);
        item.setPrice(100.0);
        item.setCategory(category);
        return item;
    }

    private SubscriptionPlan activePlan() {
        SubscriptionPlan plan = new SubscriptionPlan();
        plan.setId(1L);
        plan.setUserId(1L);
        plan.setRestaurantId(1L);
        plan.setWeekday(SubscriptionPlan.Weekday.MON);
        plan.setDeliveryTime(LocalTime.of(13, 0));
        plan.setDeliveryAddressId(10L);
        plan.setPaymentMethod("CASH_ON_DELIVERY");
        plan.setStatus(SubscriptionPlan.SubscriptionStatus.ACTIVE);
        plan.setStartDate(LocalDate.now());
        plan.setNextDeliveryDate(LocalDate.now().plusDays(1));
        plan.setItemsJson("[{\"menuItemId\":1,\"quantity\":1}]");
        return plan;
    }
}

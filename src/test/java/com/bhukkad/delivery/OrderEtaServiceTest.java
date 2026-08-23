package com.bhukkad.delivery;

import com.bhukkad.config.DeliveryTruthProperties;
import com.bhukkad.entity.Address;
import com.bhukkad.entity.DeliveryAgent;
import com.bhukkad.entity.DeliveryZone;
import com.bhukkad.entity.Order;
import com.bhukkad.entity.Restaurant;
import com.bhukkad.zone.DeliveryZoneService;
import com.bhukkad.zone.ZoneSurgeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.CALLS_REAL_METHODS;

@ExtendWith(MockitoExtension.class)
class OrderEtaServiceTest {

    private static final LocalDateTime FIXED_NOW = LocalDateTime.of(2026, 8, 22, 6, 0);
    private static final LocalDateTime MORNING_RUSH = LocalDateTime.of(2026, 8, 22, 8, 0);
    private static final LocalDateTime LUNCH_RUSH = LocalDateTime.of(2026, 8, 22, 13, 0);
    private static final LocalDateTime DINNER_RUSH = LocalDateTime.of(2026, 8, 22, 20, 0);
    private static final LocalDateTime SCHEDULED_FUTURE = FIXED_NOW.plusMinutes(120);
    private static final LocalDateTime SCHEDULED_PAST = FIXED_NOW.minusMinutes(30);

    @Mock
    private DeliveryTruthProperties properties;

    @Mock
    private DeliveryZoneService deliveryZoneService;

    @Mock
    private ZoneSurgeService zoneSurgeService;

    @Mock
    private OrderEtaHistoryService etaHistoryService;

    @Mock
    private RoadDistanceService roadDistanceService;

    @InjectMocks
    private OrderEtaService service;

    @BeforeEach
    void setUp() {
        lenient().when(properties.getConfidenceBandMinutes()).thenReturn(5);
        lenient().when(properties.getPickupBufferMinutes()).thenReturn(8);
        lenient().when(properties.getAvgSpeedKmPerMin()).thenReturn(0.6);
        lenient().when(properties.isRecordSnapshots()).thenReturn(true);
    }

    private Restaurant restaurant(Integer averageDeliveryTime, Boolean busyMode, Integer extraPrepMinutes) {
        Restaurant restaurant = new Restaurant();
        restaurant.setId(1L);
        restaurant.setAverageDeliveryTime(averageDeliveryTime);
        restaurant.setBusyMode(busyMode);
        restaurant.setExtraPrepMinutes(extraPrepMinutes);
        return restaurant;
    }

    private Address address(Double lat, Double lng) {
        Address address = new Address();
        address.setLatitude(lat);
        address.setLongitude(lng);
        return address;
    }

    private Order order(Order.OrderStatus status) {
        Order order = new Order();
        order.setId(10L);
        order.setStatus(status);
        order.setRestaurant(restaurant(30, false, 0));
        return order;
    }

    // ---------- computeLiveEta: traffic factor branches ----------

    @Test
    void computeLiveEta_morningRush_hour8_usesTrafficFactor1_1() {
        try (MockedStatic<LocalDateTime> now = mockStatic(LocalDateTime.class, CALLS_REAL_METHODS)) {
            now.when(LocalDateTime::now).thenReturn(MORNING_RUSH);

            OrderEtaService.EtaSnapshot snapshot = service.computeLiveEta(order(Order.OrderStatus.PLACED));

            assertEquals(1.1, snapshot.trafficFactor());
            assertEquals(33, snapshot.minutes());
        }
    }

    @Test
    void computeLiveEta_lunchRush_hour13_usesTrafficFactor1_15() {
        try (MockedStatic<LocalDateTime> now = mockStatic(LocalDateTime.class, CALLS_REAL_METHODS)) {
            now.when(LocalDateTime::now).thenReturn(LUNCH_RUSH);

            OrderEtaService.EtaSnapshot snapshot = service.computeLiveEta(order(Order.OrderStatus.PLACED));

            assertEquals(1.15, snapshot.trafficFactor());
            assertEquals(35, snapshot.minutes());
        }
    }

    @Test
    void computeLiveEta_dinnerRush_hour20_usesTrafficFactor1_25() {
        try (MockedStatic<LocalDateTime> now = mockStatic(LocalDateTime.class, CALLS_REAL_METHODS)) {
            now.when(LocalDateTime::now).thenReturn(DINNER_RUSH);

            OrderEtaService.EtaSnapshot snapshot = service.computeLiveEta(order(Order.OrderStatus.PLACED));

            assertEquals(1.25, snapshot.trafficFactor());
            assertEquals(38, snapshot.minutes());
        }
    }

    @Test
    void computeLiveEta_offPeak_hour6_usesTrafficFactor1_0() {
        try (MockedStatic<LocalDateTime> now = mockStatic(LocalDateTime.class, CALLS_REAL_METHODS)) {
            now.when(LocalDateTime::now).thenReturn(FIXED_NOW);

            OrderEtaService.EtaSnapshot snapshot = service.computeLiveEta(order(Order.OrderStatus.PLACED));

            assertEquals(1.0, snapshot.trafficFactor());
            assertEquals(30, snapshot.minutes());
        }
    }

    // ---------- computeLiveEta: status branches ----------

    @Test
    void computeLiveEta_placed_usesBaseDeliveryPlusPrep() {
        try (MockedStatic<LocalDateTime> now = mockStatic(LocalDateTime.class, CALLS_REAL_METHODS)) {
            now.when(LocalDateTime::now).thenReturn(FIXED_NOW);

            OrderEtaService.EtaSnapshot snapshot = service.computeLiveEta(order(Order.OrderStatus.CONFIRMED));

            assertEquals(30, snapshot.minutes());
            assertEquals(FIXED_NOW.plusMinutes(30), snapshot.etaAt());
            assertEquals(25, snapshot.confidenceLowMinutes());
            assertEquals(35, snapshot.confidenceHighMinutes());
        }
    }

    @Test
    void computeLiveEta_busyMode_addsExtraPrepMinutes() {
        try (MockedStatic<LocalDateTime> now = mockStatic(LocalDateTime.class, CALLS_REAL_METHODS)) {
            now.when(LocalDateTime::now).thenReturn(FIXED_NOW);
            Order order = order(Order.OrderStatus.PLACED);
            order.setRestaurant(restaurant(30, true, 15));

            OrderEtaService.EtaSnapshot snapshot = service.computeLiveEta(order);

            assertEquals(45, snapshot.minutes());
        }
    }

    @Test
    void computeLiveEta_busyModeWithNullExtraPrep_addsNothing() {
        try (MockedStatic<LocalDateTime> now = mockStatic(LocalDateTime.class, CALLS_REAL_METHODS)) {
            now.when(LocalDateTime::now).thenReturn(FIXED_NOW);
            Order order = order(Order.OrderStatus.PLACED);
            order.setRestaurant(restaurant(30, true, null));

            OrderEtaService.EtaSnapshot snapshot = service.computeLiveEta(order);

            assertEquals(30, snapshot.minutes());
        }
    }

    @Test
    void computeLiveEta_nullAverageDeliveryTime_usesDefaultDeliveryTime() {
        try (MockedStatic<LocalDateTime> now = mockStatic(LocalDateTime.class, CALLS_REAL_METHODS)) {
            now.when(LocalDateTime::now).thenReturn(FIXED_NOW);
            Order order = order(Order.OrderStatus.PLACED);
            order.setRestaurant(restaurant(null, false, 0));

            OrderEtaService.EtaSnapshot snapshot = service.computeLiveEta(order);

            assertEquals(30, snapshot.minutes());
        }
    }

    @Test
    void computeLiveEta_preparing_usesHalfBaseFlooredAtEight() {
        try (MockedStatic<LocalDateTime> now = mockStatic(LocalDateTime.class, CALLS_REAL_METHODS)) {
            now.when(LocalDateTime::now).thenReturn(FIXED_NOW);

            OrderEtaService.EtaSnapshot snapshot = service.computeLiveEta(order(Order.OrderStatus.PREPARING));

            assertEquals(15, snapshot.minutes());
        }
    }

    @Test
    void computeLiveEta_readyForPickup_addsPickupBuffer() {
        try (MockedStatic<LocalDateTime> now = mockStatic(LocalDateTime.class, CALLS_REAL_METHODS)) {
            now.when(LocalDateTime::now).thenReturn(FIXED_NOW);

            OrderEtaService.EtaSnapshot snapshot = service.computeLiveEta(order(Order.OrderStatus.READY_FOR_PICKUP));

            assertEquals(16, snapshot.minutes());
        }
    }

    @Test
    void computeLiveEta_delivered_returnsZeroMinutes() {
        try (MockedStatic<LocalDateTime> now = mockStatic(LocalDateTime.class, CALLS_REAL_METHODS)) {
            now.when(LocalDateTime::now).thenReturn(FIXED_NOW);

            OrderEtaService.EtaSnapshot snapshot = service.computeLiveEta(order(Order.OrderStatus.DELIVERED));

            assertEquals(0, snapshot.minutes());
            assertEquals(FIXED_NOW, snapshot.etaAt());
            assertEquals(0, snapshot.confidenceLowMinutes());
            assertEquals(5, snapshot.confidenceHighMinutes());
        }
    }

    @Test
    void computeLiveEta_cancelled_fallsThroughToDefault() {
        try (MockedStatic<LocalDateTime> now = mockStatic(LocalDateTime.class, CALLS_REAL_METHODS)) {
            now.when(LocalDateTime::now).thenReturn(FIXED_NOW);

            OrderEtaService.EtaSnapshot snapshot = service.computeLiveEta(order(Order.OrderStatus.CANCELLED));

            assertEquals(30, snapshot.minutes());
        }
    }

    @Test
    void computeLiveEta_scheduled_futureReturnsMinutesUntil() {
        try (MockedStatic<LocalDateTime> now = mockStatic(LocalDateTime.class, CALLS_REAL_METHODS)) {
            now.when(LocalDateTime::now).thenReturn(FIXED_NOW);
            Order order = order(Order.OrderStatus.SCHEDULED);
            order.setScheduledAt(SCHEDULED_FUTURE);
            assertEquals(SCHEDULED_FUTURE, order.getScheduledAt());

            OrderEtaService.EtaSnapshot snapshot = service.computeLiveEta(order);

            assertEquals(120, snapshot.minutes());
        }
    }

    @Test
    void computeLiveEta_scheduled_nullReturnsZeroMinutes() {
        try (MockedStatic<LocalDateTime> now = mockStatic(LocalDateTime.class, CALLS_REAL_METHODS)) {
            now.when(LocalDateTime::now).thenReturn(FIXED_NOW);

            OrderEtaService.EtaSnapshot snapshot = service.computeLiveEta(order(Order.OrderStatus.SCHEDULED));

            assertEquals(0, snapshot.minutes());
        }
    }

    @Test
    void computeLiveEta_scheduled_pastReturnsZeroMinutes() {
        try (MockedStatic<LocalDateTime> now = mockStatic(LocalDateTime.class, CALLS_REAL_METHODS)) {
            now.when(LocalDateTime::now).thenReturn(FIXED_NOW);
            Order order = order(Order.OrderStatus.SCHEDULED);
            order.setScheduledAt(SCHEDULED_PAST);

            OrderEtaService.EtaSnapshot snapshot = service.computeLiveEta(order);

            assertEquals(0, snapshot.minutes());
        }
    }

    // ---------- computeLiveEta: out for delivery ----------

    @Test
    void computeLiveEta_outForDelivery_noAgent_usesFallback() {
        try (MockedStatic<LocalDateTime> now = mockStatic(LocalDateTime.class, CALLS_REAL_METHODS)) {
            now.when(LocalDateTime::now).thenReturn(FIXED_NOW);
            Order order = order(Order.OrderStatus.OUT_FOR_DELIVERY);
            order.setDeliveryAddress(address(12.97, 77.59));

            OrderEtaService.EtaSnapshot snapshot = service.computeLiveEta(order);

            assertEquals(15, snapshot.minutes());
        }
    }

    @Test
    void computeLiveEta_outForDelivery_agentMissingLatitude_usesFallback() {
        try (MockedStatic<LocalDateTime> now = mockStatic(LocalDateTime.class, CALLS_REAL_METHODS)) {
            now.when(LocalDateTime::now).thenReturn(FIXED_NOW);
            Order order = order(Order.OrderStatus.OUT_FOR_DELIVERY);
            order.setDeliveryAddress(address(12.97, 77.59));
            DeliveryAgent agent = new DeliveryAgent();
            agent.setCurrentLatitude(null);
            agent.setCurrentLongitude(77.60);
            order.setDeliveryAgent(agent);

            OrderEtaService.EtaSnapshot snapshot = service.computeLiveEta(order);

            assertEquals(15, snapshot.minutes());
        }
    }

    @Test
    void computeLiveEta_outForDelivery_agentMissingLongitude_usesFallback() {
        try (MockedStatic<LocalDateTime> now = mockStatic(LocalDateTime.class, CALLS_REAL_METHODS)) {
            now.when(LocalDateTime::now).thenReturn(FIXED_NOW);
            Order order = order(Order.OrderStatus.OUT_FOR_DELIVERY);
            order.setDeliveryAddress(address(12.97, 77.59));
            DeliveryAgent agent = new DeliveryAgent();
            agent.setCurrentLatitude(12.98);
            agent.setCurrentLongitude(null);
            order.setDeliveryAgent(agent);

            OrderEtaService.EtaSnapshot snapshot = service.computeLiveEta(order);

            assertEquals(15, snapshot.minutes());
        }
    }

    @Test
    void computeLiveEta_outForDelivery_nullAddress_usesFallback() {
        try (MockedStatic<LocalDateTime> now = mockStatic(LocalDateTime.class, CALLS_REAL_METHODS)) {
            now.when(LocalDateTime::now).thenReturn(FIXED_NOW);
            Order order = order(Order.OrderStatus.OUT_FOR_DELIVERY);
            DeliveryAgent agent = new DeliveryAgent();
            agent.setCurrentLatitude(12.98);
            agent.setCurrentLongitude(77.60);
            order.setDeliveryAgent(agent);

            OrderEtaService.EtaSnapshot snapshot = service.computeLiveEta(order);

            assertEquals(15, snapshot.minutes());
        }
    }

    @Test
    void computeLiveEta_outForDelivery_addressMissingLatitude_usesFallback() {
        try (MockedStatic<LocalDateTime> now = mockStatic(LocalDateTime.class, CALLS_REAL_METHODS)) {
            now.when(LocalDateTime::now).thenReturn(FIXED_NOW);
            Order order = order(Order.OrderStatus.OUT_FOR_DELIVERY);
            order.setDeliveryAddress(address(null, 77.59));
            DeliveryAgent agent = new DeliveryAgent();
            agent.setCurrentLatitude(12.98);
            agent.setCurrentLongitude(77.60);
            order.setDeliveryAgent(agent);

            OrderEtaService.EtaSnapshot snapshot = service.computeLiveEta(order);

            assertEquals(15, snapshot.minutes());
        }
    }

    @Test
    void computeLiveEta_outForDelivery_addressMissingLongitude_usesFallback() {
        try (MockedStatic<LocalDateTime> now = mockStatic(LocalDateTime.class, CALLS_REAL_METHODS)) {
            now.when(LocalDateTime::now).thenReturn(FIXED_NOW);
            Order order = order(Order.OrderStatus.OUT_FOR_DELIVERY);
            order.setDeliveryAddress(address(12.97, null));
            DeliveryAgent agent = new DeliveryAgent();
            agent.setCurrentLatitude(12.98);
            agent.setCurrentLongitude(77.60);
            order.setDeliveryAgent(agent);

            OrderEtaService.EtaSnapshot snapshot = service.computeLiveEta(order);

            assertEquals(15, snapshot.minutes());
        }
    }

    @Test
    void computeLiveEta_outForDelivery_osrmRoute_usesDuration() {
        try (MockedStatic<LocalDateTime> now = mockStatic(LocalDateTime.class, CALLS_REAL_METHODS)) {
            now.when(LocalDateTime::now).thenReturn(FIXED_NOW);
            Order order = order(Order.OrderStatus.OUT_FOR_DELIVERY);
            order.setDeliveryAddress(address(12.97, 77.59));
            DeliveryAgent agent = new DeliveryAgent();
            agent.setCurrentLatitude(12.98);
            agent.setCurrentLongitude(77.60);
            order.setDeliveryAgent(agent);
            when(roadDistanceService.route(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                    .thenReturn(new RoadDistanceService.RoadRoute(2.5, 20.5, true));

            OrderEtaService.EtaSnapshot snapshot = service.computeLiveEta(order);

            assertEquals(21, snapshot.minutes());
        }
    }

    @Test
    void computeLiveEta_outForDelivery_haversineRoute_usesDistanceAndSpeed() {
        try (MockedStatic<LocalDateTime> now = mockStatic(LocalDateTime.class, CALLS_REAL_METHODS)) {
            now.when(LocalDateTime::now).thenReturn(FIXED_NOW);
            Order order = order(Order.OrderStatus.OUT_FOR_DELIVERY);
            order.setDeliveryAddress(address(12.97, 77.59));
            DeliveryAgent agent = new DeliveryAgent();
            agent.setCurrentLatitude(12.98);
            agent.setCurrentLongitude(77.60);
            order.setDeliveryAgent(agent);
            when(roadDistanceService.route(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                    .thenReturn(new RoadDistanceService.RoadRoute(12.5, 40.0, false));

            OrderEtaService.EtaSnapshot snapshot = service.computeLiveEta(order);

            assertEquals(21, snapshot.minutes());
        }
    }

    @Test
    void computeLiveEta_outForDelivery_shortDistance_floorAtFive() {
        try (MockedStatic<LocalDateTime> now = mockStatic(LocalDateTime.class, CALLS_REAL_METHODS)) {
            now.when(LocalDateTime::now).thenReturn(FIXED_NOW);
            Order order = order(Order.OrderStatus.OUT_FOR_DELIVERY);
            order.setDeliveryAddress(address(12.97, 77.59));
            DeliveryAgent agent = new DeliveryAgent();
            agent.setCurrentLatitude(12.98);
            agent.setCurrentLongitude(77.60);
            order.setDeliveryAgent(agent);
            when(roadDistanceService.route(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                    .thenReturn(new RoadDistanceService.RoadRoute(0.1, 0.2, false));

            OrderEtaService.EtaSnapshot snapshot = service.computeLiveEta(order);

            assertEquals(5, snapshot.minutes());
        }
    }

    // ---------- computeLiveEta: surge ----------

    @Test
    void computeLiveEta_noZoneForCoordinates_surgeIsOne() {
        try (MockedStatic<LocalDateTime> now = mockStatic(LocalDateTime.class, CALLS_REAL_METHODS)) {
            now.when(LocalDateTime::now).thenReturn(FIXED_NOW);
            Order order = order(Order.OrderStatus.PLACED);
            order.setDeliveryAddress(address(12.97, 77.59));
            when(deliveryZoneService.findZoneForCoordinates(12.97, 77.59))
                    .thenReturn(Optional.empty());

            OrderEtaService.EtaSnapshot snapshot = service.computeLiveEta(order);

            assertEquals(1.0, snapshot.surgeMultiplier());
        }
    }

    @Test
    void computeLiveEta_zoneResolvesEffectiveSurge() {
        try (MockedStatic<LocalDateTime> now = mockStatic(LocalDateTime.class, CALLS_REAL_METHODS)) {
            now.when(LocalDateTime::now).thenReturn(FIXED_NOW);
            Order order = order(Order.OrderStatus.PLACED);
            order.setDeliveryAddress(address(12.97, 77.59));
            DeliveryZone zone = new DeliveryZone();
            zone.setId(1L);
            when(deliveryZoneService.findZoneForCoordinates(12.97, 77.59))
                    .thenReturn(Optional.of(zone));
            when(zoneSurgeService.resolveEffectiveSurge(zone)).thenReturn(2.0);

            OrderEtaService.EtaSnapshot snapshot = service.computeLiveEta(order);

            assertEquals(2.0, snapshot.surgeMultiplier());
        }
    }

    // ---------- applyLiveEta ----------

    @Test
    void applyLiveEta_setsFieldsAndRecordsSnapshot() {
        try (MockedStatic<LocalDateTime> now = mockStatic(LocalDateTime.class, CALLS_REAL_METHODS)) {
            now.when(LocalDateTime::now).thenReturn(FIXED_NOW);

            Order order = order(Order.OrderStatus.PLACED);
            service.applyLiveEta(order);

            assertEquals(30, order.getLiveEtaMinutes());
            assertEquals(FIXED_NOW.plusMinutes(30), order.getLiveEtaAt());
            verify(etaHistoryService).recordSnapshot(eq(order), any(OrderEtaService.EtaSnapshot.class),
                    eq(1.0), eq(1.0), any(String.class));
        }
    }

    @Test
    void applyLiveEta_snapshotNotRecordedWhenOrderIdNull() {
        try (MockedStatic<LocalDateTime> now = mockStatic(LocalDateTime.class, CALLS_REAL_METHODS)) {
            now.when(LocalDateTime::now).thenReturn(FIXED_NOW);
            Order order = order(Order.OrderStatus.PLACED);
            order.setId(null);

            service.applyLiveEta(order);

            assertEquals(30, order.getLiveEtaMinutes());
            verifyNoInteractions(etaHistoryService);
        }
    }

    @Test
    void applyLiveEta_snapshotNotRecordedWhenDisabled() {
        try (MockedStatic<LocalDateTime> now = mockStatic(LocalDateTime.class, CALLS_REAL_METHODS)) {
            now.when(LocalDateTime::now).thenReturn(FIXED_NOW);
            when(properties.isRecordSnapshots()).thenReturn(false);

            service.applyLiveEta(order(Order.OrderStatus.PLACED));

            verify(etaHistoryService, never()).recordSnapshot(
                    any(), any(), anyDouble(), anyDouble(), any(String.class));
        }
    }
}

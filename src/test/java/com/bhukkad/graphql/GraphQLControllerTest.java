package com.bhukkad.graphql;

import com.bhukkad.cache.HomeFeedCacheService;
import com.bhukkad.dto.response.MembershipPlanResponse;
import com.bhukkad.dto.response.OrderResponse;
import com.bhukkad.dto.response.PromoBannerResponse;
import com.bhukkad.dto.response.PromotionCampaignResponse;
import com.bhukkad.feed.PromoBannerService;
import com.bhukkad.membership.MembershipService;
import com.bhukkad.promotion.PromotionCampaignService;
import com.bhukkad.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the GraphQL resolvers in {@link GraphQLController}.
 *
 * <p>The resolvers must stay thin pass-throughs over the same services the
 * REST controllers use, so these tests pin two contracts: the home feed is
 * assembled from the cached loaders wired to the right source services, and
 * the field-level mappers never violate the non-null GraphQL schema contract.
 */
@ExtendWith(MockitoExtension.class)
class GraphQLControllerTest {

    @Mock
    private PromoBannerService promoBannerService;
    @Mock
    private PromotionCampaignService promotionCampaignService;
    @Mock
    private MembershipService membershipService;
    @Mock
    private HomeFeedCacheService homeFeedCacheService;
    @Mock
    private OrderService orderService;

    private GraphQLController controller;

    @BeforeEach
    void setUp() {
        controller = new GraphQLController(
                promoBannerService,
                promotionCampaignService,
                membershipService,
                homeFeedCacheService,
                orderService);
    }

    // ---------------------------------------------------------------
    // homeFeed
    // ---------------------------------------------------------------

    @Test
    void homeFeed_assemblesAllThreeSectionsFromCachedLoaders() {
        List<PromoBannerResponse> banners = List.of(
                PromoBannerResponse.builder().id(1L).title("Diwali").build());
        List<PromotionCampaignResponse> campaigns = List.of(
                PromotionCampaignResponse.builder().id(2L).name("Monsoon Sale").build());
        List<MembershipPlanResponse> plans = List.of(
                MembershipPlanResponse.builder().id(3L).name("Gold").build());
        when(promoBannerService.listActive()).thenReturn(banners);
        when(promotionCampaignService.listActive()).thenReturn(campaigns);
        when(membershipService.listPlans()).thenReturn(plans);
        // The cache service computes each section through the loader the
        // controller passes in — invoke it here so the wiring is proven.
        when(homeFeedCacheService.getBanners(any())).thenAnswer(this::invokeLoader);
        when(homeFeedCacheService.getCampaigns(any())).thenAnswer(this::invokeLoader);
        when(homeFeedCacheService.getMembershipPlans(any())).thenAnswer(this::invokeLoader);

        GraphQLController.HomeFeed feed = controller.homeFeed();

        assertEquals(banners, feed.banners());
        assertEquals(campaigns, feed.campaigns());
        assertEquals(plans, feed.membershipPlans());
        verify(promoBannerService).listActive();
        verify(promotionCampaignService).listActive();
        verify(membershipService).listPlans();
    }

    @Test
    void homeFeed_withEmptySections_returnsEmptyListsPerSection() {
        when(promoBannerService.listActive()).thenReturn(List.of());
        when(promotionCampaignService.listActive()).thenReturn(List.of());
        when(membershipService.listPlans()).thenReturn(List.of());
        when(homeFeedCacheService.getBanners(any())).thenAnswer(this::invokeLoader);
        when(homeFeedCacheService.getCampaigns(any())).thenAnswer(this::invokeLoader);
        when(homeFeedCacheService.getMembershipPlans(any())).thenAnswer(this::invokeLoader);

        GraphQLController.HomeFeed feed = controller.homeFeed();

        assertNotNull(feed.banners());
        assertNotNull(feed.campaigns());
        assertNotNull(feed.membershipPlans());
        assertEquals(0, feed.banners().size());
        assertEquals(0, feed.campaigns().size());
        assertEquals(0, feed.membershipPlans().size());
    }

    @SuppressWarnings("unchecked")
    private Object invokeLoader(org.mockito.invocation.InvocationOnMock invocation) {
        Supplier<Object> loader = (Supplier<Object>) invocation.getArgument(0);
        return loader.get();
    }

    // ---------------------------------------------------------------
    // order
    // ---------------------------------------------------------------

    @Test
    void order_parsesStringIdAndMapsResponse() {
        OrderResponse response = new OrderResponse();
        response.setId(42L);
        response.setOrderNumber("ORD-42");
        response.setStatus("PREPARING");
        response.setCustomerName("Alice");
        response.setRestaurantName("Spice Hub");
        response.setTotalAmount(550.0);
        response.setSubtotal(500.0);
        response.setDeliveryFee(30.0);
        response.setTaxAmount(20.0);
        response.setTipAmount(0.0);
        response.setCreatedAt(LocalDateTime.of(2026, 1, 1, 12, 0));
        when(orderService.getOrderById(42L)).thenReturn(response);

        GraphQLController.Order order = controller.order("42");

        assertEquals(42L, order.id());
        assertEquals("ORD-42", order.orderNumber());
        assertEquals("PREPARING", order.status());
        assertEquals("Alice", order.customerName());
        assertEquals("Spice Hub", order.restaurantName());
        assertEquals(550.0, order.totalAmount());
        assertEquals(500.0, order.subtotal());
        assertEquals(30.0, order.deliveryFee());
        assertEquals(20.0, order.taxAmount());
        assertEquals(0.0, order.tipAmount());
        assertEquals("2026-01-01T12:00", order.createdAt());
    }

    @Test
    void order_withNullCreatedAt_mapsCreatedAtToNull() {
        OrderResponse response = new OrderResponse();
        response.setId(7L);
        when(orderService.getOrderById(7L)).thenReturn(response);

        GraphQLController.Order order = controller.order("7");

        assertNull(order.createdAt(), "null createdAt must serialise as null, not throw");
    }

    @Test
    void order_withNonNumericId_propagatesParseErrorWithoutTouchingService() {
        assertThrows(NumberFormatException.class, () -> controller.order("not-a-number"));
        verifyNoInteractions(orderService);
    }

    // ---------------------------------------------------------------
    // Field-level mappings
    // ---------------------------------------------------------------

    @Test
    void bannerTitle_returnsTitle_whenTitlePresent() {
        PromoBannerResponse banner = PromoBannerResponse.builder()
                .title("Diwali Offer")
                .subtitle("Save big")
                .build();

        assertEquals("Diwali Offer", controller.bannerTitle(banner));
    }

    @Test
    void bannerTitle_fallsBackToSubtitle_whenTitleMissing() {
        PromoBannerResponse banner = PromoBannerResponse.builder()
                .title(null)
                .subtitle("Legacy banner subtitle")
                .build();

        assertEquals("Legacy banner subtitle", controller.bannerTitle(banner));
    }

    @Test
    void campaignName_returnsCampaignName() {
        PromotionCampaignResponse campaign = PromotionCampaignResponse.builder()
                .name("Monsoon Sale")
                .build();

        assertEquals("Monsoon Sale", controller.campaignName(campaign));
    }

    @Test
    void planName_returnsPlanName_whenPresent() {
        MembershipPlanResponse plan = MembershipPlanResponse.builder()
                .name("Gold")
                .build();

        assertEquals("Gold", controller.planName(plan));
    }

    @Test
    void planName_returnsEmptyString_whenNameMissing() {
        MembershipPlanResponse plan = MembershipPlanResponse.builder()
                .name(null)
                .build();

        assertEquals("", controller.planName(plan), "schema declares name non-null; nulls map to empty string");
    }
}

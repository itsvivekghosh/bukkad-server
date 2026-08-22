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
import lombok.RequiredArgsConstructor;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;

import java.util.List;

/**
 * GraphQL surface for the two heaviest reads in the mobile app:
 *
 * <ul>
 *   <li>{@code homeFeed} — the three sections of the launch screen in a
 *       single round-trip, with field selection so the client only receives
 *       what it renders;</li>
 *   <li>{@code order(id)} — order detail, reusing
 *       {@link OrderService#getOrderById} so the existing ownership check
 *       and 404/401 paths still apply unchanged.</li>
 * </ul>
 *
 * <p>Resolvers are deliberately thin — they delegate to the same services
 * the REST controllers call, which means the REST and GraphQL paths cannot
 * drift in behaviour. The only new code is the schema and the field-level
 * mappers below.
 *
 * <p>Endpoint is mounted by spring-graphql at {@code POST /graphql} with
 * GraphiQL UI at {@code /graphiql} in dev profile (the auto-configured UI is
 * disabled in prod via {@code spring.graphql.cors.allowed-origins} and the
 * existing security config which already requires auth on the endpoint).
 */
@Controller
@RequiredArgsConstructor
public class GraphQLController {

    private final PromoBannerService promoBannerService;
    private final PromotionCampaignService promotionCampaignService;
    private final MembershipService membershipService;
    private final HomeFeedCacheService homeFeedCacheService;
    private final OrderService orderService;

    /**
     * Single-query home feed. Each section comes from the same cached method
     * the REST endpoint uses, so a cache miss on one section does not force
     * the others to be recomputed.
     */
    @QueryMapping
    public HomeFeed homeFeed() {
        return new HomeFeed(
                homeFeedCacheService.getBanners(promoBannerService::listActive),
                homeFeedCacheService.getCampaigns(promotionCampaignService::listActive),
                homeFeedCacheService.getMembershipPlans(membershipService::listPlans));
    }

    /**
     * Customer order detail. Reuses {@link OrderService#getOrderById} so the
     * 401/404 contract is identical to {@code GET /api/v1/orders/customer/{id}}.
     */
    @QueryMapping
    public Order order(String id) {
        Long orderId = Long.parseLong(id);
        OrderResponse response = orderService.getOrderById(orderId);
        return Order.from(response);
    }

    // ---------------------------------------------------------------
    // Field-level mappings — translate existing REST DTOs into the
    // narrower GraphQL types. Doing it at field level rather than with a
    // custom serializer keeps each mapper trivially testable.
    // ---------------------------------------------------------------

    @SchemaMapping(typeName = "PromoBanner", field = "title")
    public String bannerTitle(PromoBannerResponse banner) {
        // Fall back to subtitle when title is missing so the GraphQL
        // contract (title is non-null) holds even for legacy banners.
        return banner.getTitle() != null ? banner.getTitle() : banner.getSubtitle();
    }

    @SchemaMapping(typeName = "PromotionCampaign", field = "name")
    public String campaignName(PromotionCampaignResponse campaign) {
        return campaign.getName();
    }

    @SchemaMapping(typeName = "MembershipPlan", field = "name")
    public String planName(MembershipPlanResponse plan) {
        return plan.getName() != null ? plan.getName() : "";
    }

    /** Container for the home feed result. Lives only as a transport type. */
    public record HomeFeed(List<PromoBannerResponse> banners,
                           List<PromotionCampaignResponse> campaigns,
                           List<MembershipPlanResponse> membershipPlans) {}

    /** GraphQL view of an order — wraps {@link OrderResponse} with the
     *  fields the schema exposes. Static mapper keeps the resolver one-liner. */
    public record Order(Long id, String orderNumber, String status, String customerName,
                        String restaurantName, Double totalAmount, Double subtotal,
                        Double deliveryFee, Double taxAmount, Double tipAmount,
                        String createdAt) {
        public static Order from(OrderResponse r) {
            return new Order(
                    r.getId(),
                    r.getOrderNumber(),
                    r.getStatus(),
                    r.getCustomerName(),
                    r.getRestaurantName(),
                    r.getTotalAmount(),
                    r.getSubtotal(),
                    r.getDeliveryFee(),
                    r.getTaxAmount(),
                    r.getTipAmount(),
                    r.getCreatedAt() != null ? r.getCreatedAt().toString() : null);
        }
    }
}

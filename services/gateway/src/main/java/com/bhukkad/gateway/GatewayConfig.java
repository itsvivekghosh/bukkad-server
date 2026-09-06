package com.bhukkad.gateway;

import com.bhukkad.gateway.flags.EdgeKillSwitchFilter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Strangler route table (P0 + P4 first slice).
 *
 * <p>Routes are path-driven so cutting over a domain is a predicate edit only:
 * the restaurant slice is served by the {@code restaurant} service, everything
 * else falls through to the monolith ({@code bhukkad-app}) as the safe default.
 * Backend URIs are k8s Service DNS names — no service-discovery stack needed.</p>
 *
 * <p>Cutover order follows the ownership matrix (§3): identity (auth) →
 * restaurant (read slice) → order → payment → delivery → notification →
 * admin-analytics → monolith teardown.</p>
 */
@Configuration
public class GatewayConfig {

    private final String restaurantUri;
    private final String identityUri;
    private final String orderUri;
    private final String paymentUri;
    private final String deliveryUri;
    private final String searchUri;
    private final String surveyUri;
    private final String referralUri;
    private final String supportUri;
    private final String notificationUri;
    private final String adminAnalyticsUri;
    private final String realtimeUri;
    private final String growthUri;

    public GatewayConfig(@Value("${app.routes.restaurant-uri}") String restaurantUri,
                         @Value("${app.routes.identity-uri}") String identityUri,
                         @Value("${app.routes.order-uri}") String orderUri,
                         @Value("${app.routes.payment-uri}") String paymentUri,
                         @Value("${app.routes.delivery-uri}") String deliveryUri,
                         @Value("${app.routes.search-uri}") String searchUri,
                         @Value("${app.routes.survey-uri}") String surveyUri,
                         @Value("${app.routes.referral-uri}") String referralUri,
                         @Value("${app.routes.support-uri}") String supportUri,
                         @Value("${app.routes.notification-uri}") String notificationUri,
                         @Value("${app.routes.admin-analytics-uri}") String adminAnalyticsUri,
                             @Value("${app.routes.realtime-uri}") String realtimeUri,
    @Value("${app.routes.growth-uri}") String growthUri) {
        this.restaurantUri = restaurantUri;
        this.identityUri = identityUri;
        this.orderUri = orderUri;
        this.paymentUri = paymentUri;
        this.deliveryUri = deliveryUri;
        this.searchUri = searchUri;
        this.surveyUri = surveyUri;
        this.referralUri = referralUri;
        this.supportUri = supportUri;
        this.notificationUri = notificationUri;
        this.adminAnalyticsUri = adminAnalyticsUri;
        this.realtimeUri = realtimeUri;
        this.growthUri = growthUri;
    }

    /**
     * Defines the path → backend routing table.
     *
     * @return the {@link RouteLocator} consulted by the gateway on every request
     */
    @Bean
    public RouteLocator gatewayRoutes(RouteLocatorBuilder builder) {
        return builder.routes()
                // Notification service (P2): notification dispatch and history.
                .route("notification", r -> r.path(
                        "/api/v1/notifications/**").uri(notificationUri))
                // Survey service (P2): survey submission, survey ratings and
                // trending dishes. Declared FIRST: /api/v1/reviews/survey and
                // /api/v1/restaurants/public/*/survey-ratings are narrower
                // than the restaurant slice's /api/v1/reviews/** and
                // /api/v1/restaurants/** predicates and must win.
                .route("survey", r -> r.path(
                        "/api/v1/reviews/survey",
                        "/api/v1/restaurants/public/*/survey-ratings",
                        "/api/v1/home/trending").metadata(EdgeKillSwitchFilter.ROUTE_FLAG_METADATA, "edge.survey.enabled")
                        .uri(surveyUri))
                // Customer sub-resource carve-outs — narrower than the generic
                // /api/v1/customers/** slice that falls to the identity/monolith
                // route, so they are declared before it.
                .route("customer-cart", r -> r.path(
                        "/api/v1/customers/*/cart/**",
                        "/api/v1/customers/*/reorder/**").uri(orderUri))
                .route("customer-wallet", r -> r.path(
                        "/api/v1/customers/*/wallet/**").uri(paymentUri))
                .route("customer-group-orders", r -> r.path(
                        "/api/v1/customers/*/group-orders/**").uri(orderUri))
                .route("customer-subscriptions", r -> r.path(
                        "/api/v1/customers/*/subscriptions/**").uri(orderUri))
                // Home/mobile BFF surfaces (monolith parity): composite feed is
                // served by the restaurant service; campaigns and membership
                // plans are narrow rewrites onto the owning services.
                .route("home-feed", r -> r.path(
                        "/api/v1/home/feed",
                        "/api/v1/home/banners",
                        "/api/v1/mobile/feed").metadata(EdgeKillSwitchFilter.ROUTE_FLAG_METADATA, "edge.feed.enabled")
                        .uri(restaurantUri))
                .route("home-campaigns", r -> r.path("/api/v1/home/campaigns")
                        .filters(f -> f.rewritePath("/api/v1/home/campaigns", "/api/v1/campaigns/active"))
                        .uri(growthUri))
                .route("home-membership", r -> r.path("/api/v1/home/membership-plans")
                        .filters(f -> f.rewritePath("/api/v1/home/membership-plans", "/api/v1/membership/plans"))
                        .uri(identityUri))
                // Cache ops surface (platform-lib controller) — served by the
                // admin-analytics deployment; clears are ADMIN-gated there.
                .route("cache", r -> r.path(
                        "/api/v1/cache/**").uri(adminAnalyticsUri))
                // Platform status: identity carries the shared HealthController.
                .route("platform", r -> r.path(
                        "/api/v1/platform/**").uri(identityUri))

                // Search service (P1): unified search + autocomplete.
                .route("search", r -> r.path(
                        "/api/v1/search/**").uri(searchUri))
                // Referral service (P2): referral codes + affiliate program.
                // Declared before "restaurant"/"monolith" slices.
                .route("referral", r -> r.path(
                        "/api/v1/referrals/**",
                        "/api/v1/admin/affiliates/**").metadata(EdgeKillSwitchFilter.ROUTE_FLAG_METADATA, "edge.referral.enabled")
                        .uri(referralUri))
                // Support ticket service (P2): ticket lifecycle.
                .route("support", r -> r.path(
                        "/api/v1/support/**").metadata(EdgeKillSwitchFilter.ROUTE_FLAG_METADATA, "edge.support.enabled")
                        .uri(supportUri))
                // Live order stream (P2): SSE kitchen/rider/customer streams
                // and anonymous tracking tokens. Narrower than /api/v1/orders/**
                // so declared before the order slice. Served by the realtime
                // service (strangler of the monolith live slice); gated by the
                // edge live kill switch.
                .route("live", r -> r.path(
                        "/api/v1/orders/stream/**",
                        "/api/v1/live/**").metadata(EdgeKillSwitchFilter.ROUTE_FLAG_METADATA, "edge.live.enabled")
                        .uri(realtimeUri))
                // Growth service: campaigns + customer loyalty accounts.
                .route("growth", r -> r.path(
                        "/api/v1/campaigns/**",
                        "/api/v1/customers/*/loyalty/**").uri(growthUri))
                // Inventory alerts (P2): restaurant inventory alert endpoints.
                // Narrower than /api/v1/restaurants/** so declared before it.
                .route("inventory", r -> r.path(
                        "/api/v1/inventory/alerts/**").metadata(EdgeKillSwitchFilter.ROUTE_FLAG_METADATA, "edge.inventory.enabled")
                        .uri(restaurantUri))
                // Strangler slice: restaurant read surface (owned by the
                // restaurant service per the ownership matrix).
                .route("restaurant", r -> r.path(
                        "/api/v1/restaurants/**",
                        "/api/v1/cuisines/**",
                        "/api/v1/menu/**",
                        "/api/v1/reviews/**",
                        "/api/v1/feed/**").uri(restaurantUri))
                // Identity cut-over (P3): auth endpoints served by the identity
                // service; everything else on /api/** still falls to the monolith.
                // Declared before "monolith" so /api/v1/auth/** wins the match.
                .route("identity", r -> r.path(
                        "/api/v1/auth/**",
                        "/api/v1/customers/**",
                        "/api/v1/tenants/**",
                        "/api/v1/affiliate/**",
                        "/api/v1/health/**").uri(identityUri))
                // Order-service strangler slice (P5): order/cart endpoints.
                // Order is gated on restaurant + payment + delivery extraction.
                // Coupon + dispute surfaces extracted in P2 land here too.
                .route("order", r -> r.path(
                        "/api/v1/orders/**",
                        "/api/v1/coupons/**",
                        "/api/v1/gift-cards/**",
                        "/api/v1/admin/disputes/**").metadata(EdgeKillSwitchFilter.ROUTE_FLAG_METADATA, "edge.order.enabled")
                        .uri(orderUri))
                // Payment service (P6): payment endpoints.
                .route("payment", r -> r.path(
                        "/api/v1/payments/**",
                        "/api/v1/wallet/**").metadata(EdgeKillSwitchFilter.ROUTE_FLAG_METADATA, "edge.payment.enabled")
                        .uri(paymentUri))
                // Delivery service (P7): delivery endpoints.
                .route("delivery", r -> r.path(
                        "/api/v1/delivery/**",
                        "/api/v1/deliveries/**",
                        "/api/v1/zones/**",
                        "/api/v1/serviceability/**").metadata(EdgeKillSwitchFilter.ROUTE_FLAG_METADATA, "edge.delivery.enabled")
                        .uri(deliveryUri))
                // Admin-analytics service (P3): platform admin, fraud, feature
                // flags, tenants, compliance, analytics exports.
                // /api/v1/admin/affiliates/** is already captured by the referral
                // route declared above; /api/v1/admin/disputes/** is captured by
                // the order route above.
                .route("admin-analytics", r -> r.path(
                        "/api/v1/admin/**").uri(adminAnalyticsUri))
                .build();
    }
}

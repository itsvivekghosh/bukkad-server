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
 * each domain slice is served by its owning microservice. Backend URIs are k8s
 * Service DNS names — no service-discovery stack needed.</p>
 *
 * <p>Path predicates are declared from most-specific to least-specific: narrow
 * customer carve-outs, survey sub-paths, live order streams, etc. precede the
 * broad identity / order / restaurant slices. The table is closed with a
 * catch-all {@code http_status: 404} route so no request silently falls through
 * to a legacy monolith (audit-guide V-20). Cutover order follows the ownership
 * matrix (§3): identity (auth) → restaurant (read slice) → order → payment →
 * delivery → notification → admin-analytics → monolith teardown.</p>
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
    private final String personalizationUri;

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
     @Value("${app.routes.growth-uri}") String growthUri,
     @Value("${app.routes.personalization-uri}") String personalizationUri) {
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
        this.personalizationUri = personalizationUri;
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
                // /api/v1/customers/** slice served by the identity route,
                // so they are declared before it.
                .route("customer-cart", r -> r.path(
                        "/api/v1/customers/*/cart/**",
                        "/api/v1/customers/*/reorder/**").uri(orderUri))
                .route("customer-wallet", r -> r.path(
                        "/api/v1/customers/*/wallet/**",
                        // Self-scoped wallet surface (token subject, no path id).
                        "/api/v1/customers/wallet/**").uri(paymentUri))
                .route("customer-group-orders", r -> r.path(
                        "/api/v1/customers/*/group-orders/**",
                        // Self-scoped group orders (token subject, no path id).
                        "/api/v1/customers/group-orders/**").uri(orderUri))
                .route("customer-subscriptions", r -> r.path(
                        "/api/v1/customers/*/subscriptions/**",
                        // Self-scoped subscriptions (token subject, no path id).
                        "/api/v1/customers/subscriptions/**").uri(orderUri))
                // Self-scoped order stats + disputes (token subject, no path id).
                .route("customer-order-extras", r -> r.path(
                        "/api/v1/customers/orders/stats",
                        "/api/v1/customers/disputes").uri(orderUri))
                // Customer loyalty self surface — growth owns loyalty accounts.
                .route("customer-loyalty-self", r -> r.path(
                        "/api/v1/customers/loyalty-points").uri(growthUri))
                // Customer support self surface — rewrite onto the support
                // ticket service's canonical /api/v1/support/tickets paths.
                .route("customer-support-self", r -> r.path(
                        "/api/v1/customers/support/tickets")
                        .filters(f -> f.rewritePath("/api/v1/customers/support/tickets",
                                "/api/v1/support/tickets"))
                        .uri(supportUri))
                // Customer recommendations self surface — personalization owns
                // the recommendation engines.
                .route("customer-recommendations", r -> r.path(
                        "/api/v1/customers/me/recommendations/**").uri(personalizationUri))
                // "Surprise me" picks a random item from the restaurant domain.
                .route("customer-surprise-me", r -> r.path(
                        "/api/v1/customers/surprise-me").uri(restaurantUri))
                // Customer order history + checkout (monolith parity: the app
                // places orders under this path). Narrower than the customers
                // slice, so it must precede it.
                .route("customer-orders", r -> r.path(
                        "/api/v1/customers/*/orders").uri(orderUri))
                // Legacy (monolith-parity) cart surface — mobile clients still
                // call /api/v1/cart/**; served by the order service from the
                // token subject.
                .route("cart-legacy", r -> r.path(
                        "/api/v1/cart/**").uri(orderUri))
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
                // Customer self-service compliance surface (DPDP): consents and
                // data export served by the identity service's consent store.
                .route("compliance", r -> r.path(
                        "/api/v1/compliance/**").uri(identityUri))
                // Analytics CSV exports (admin-analytics owns the export tasks).
                .route("analytics-exports", r -> r.path(
                        "/api/v1/analytics/**").uri(adminAnalyticsUri))
                // Swagger UI + OpenAPI documents (identity hosts the aggregate).
                .route("swagger", r -> r.path(
                        "/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**",
                        "/api-docs/**").uri(identityUri))

                // Search service (P1): unified search + autocomplete.
                .route("search", r -> r.path(
                        "/api/v1/search/**").uri(searchUri))
                // Referral service (P2): referral codes + affiliate program.
                // Declared before the restaurant slice.
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
                // so declared before the order slice. Order ownership (customer
                // stream) is enforced inside the order service, which also owns
                // the order read-model; /api/v1/live/** remains on realtime.
                .route("live", r -> r.path(
                        "/api/v1/orders/stream/**").metadata(EdgeKillSwitchFilter.ROUTE_FLAG_METADATA, "edge.order.enabled")
                        .uri(orderUri))
                .route("live-realtime", r -> r.path(
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
                // service. Declared before the broad slices so /api/v1/auth/**
                // and /api/v1/customers/** win their respective path matches.
                .route("identity", r -> r.path(
                        "/api/v1/auth/**",
                        "/api/v1/customers/**",
                        "/api/v1/tenants/**",
                        "/api/v1/affiliate/**",
                        "/api/v1/health/**").uri(identityUri))
                // Personalization service: recommendation feed ranking +
                // item-to-item similarity served by the personalization service.
                .route("personalization", r -> r.path(
                        "/api/v1/recommendations/**",
                        "/api/v1/feed/ranked/**").metadata(EdgeKillSwitchFilter.ROUTE_FLAG_METADATA, "edge.personalization.enabled")
                        .uri(personalizationUri))
                // Order-service strangler slice (P5): order/cart endpoints.
                // Order is gated on restaurant + payment + delivery extraction.
                // Coupon + dispute surfaces extracted in P2 land here too.
                .route("order", r -> r.path(
                        "/api/v1/orders/**",
                        "/api/v1/delivery-truth/**",
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
                // Restaurant administration (platform-admin actions on the
                // restaurant domain) — narrower than /api/v1/admin/**. The
                // /stats dashboard stays on admin-analytics.
                .route("admin-restaurant-stats", r -> r.path(
                        "/api/v1/admin/restaurants/stats").uri(adminAnalyticsUri))
                .route("admin-restaurants", r -> r.path(
                        "/api/v1/admin/restaurants/**").uri(restaurantUri))
                // Platform commission surface (restaurant domain).
                .route("commission", r -> r.path(
                        "/api/v1/commission/**").uri(restaurantUri))
                // Admin-analytics service (P3): platform admin, fraud, feature
                // flags, tenants, compliance, analytics exports.
                // /api/v1/admin/affiliates/** is already captured by the referral
                // route declared above; /api/v1/admin/disputes/** is captured by
                // the order route above.
                .route("admin-analytics", r -> r.path(
                        "/api/v1/admin/**").uri(adminAnalyticsUri))
                // Catch-all: any unmatched path returns 404 instead of silently
                // falling through to a legacy monolith (audit-guide V-20).
                .route("not-found", r -> r.path("/**")
                        .filters(f -> f.setStatus(404))
                        .uri(orderUri))
                .build();
    }
}

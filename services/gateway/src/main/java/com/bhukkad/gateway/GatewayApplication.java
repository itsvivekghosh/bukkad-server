package com.bhukkad.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Bhukkad API Gateway (P0).
 *
 * <p>Reactive edge gateway in front of the monolith and the per-service
 * deployments. Routes by path so a strangler flip is an edit to route
 * predicates only, with no service redeploy. Authentication is passthrough:
 * each service validates its own JWT via {@code platform-lib}'s
 * {@code PlatformJwtAuthFilter}, so the gateway does not inspect tokens.</p>
 *
 * <p>Service discovery is intentionally NOT used (architecture §1.3): routes
 * point at the services' k8s Service DNS names (e.g.
 * {@code bhukkad-restaurant.bhukkad.svc.cluster.local}), so no Spring Cloud
 * Kubernetes dependency is required.</p>
 *
 * <p>Autoconfiguration is disabled in {@code application.yml}: platform-lib
 * carries a non-optional {@code spring-boot-starter-data-jpa} (every service
 * owns a database) and {@code spring-security-oauth2-jose}, but the gateway is
 * a stateless edge router with no datasource and is auth-passthrough — so the
 * JPA/JDBC, transaction, and reactive-security auto-configurations are excluded.
 * Component scan is likewise limited to this package: platform-lib's servlet
 * {@code GlobalExceptionHandler} and servlet JWT filter are incompatible with
 * this WebFlux edge; reactive-safe primitives ({@code TraceContext},
 * {@code BusinessMetrics}) stay on the classpath for explicit import when
 * tracing/metrics propagation is wired.</p>
 */
@SpringBootApplication(scanBasePackages = "com.bhukkad.gateway")
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}

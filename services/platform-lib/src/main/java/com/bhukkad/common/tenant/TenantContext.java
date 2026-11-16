package com.bhukkad.common.tenant;

import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.hibernate.engine.spi.SessionImplementor;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Current tenant identifier resolved from a {@link ThreadLocal}.
 *
 * <p>The tenant id is bound per request (for example by a servlet filter that
 * reads the {@code X-Tenant-Id} header) via {@link #setTenantId(String)} and
 * cleared in a {@code finally} block via {@link #clearTenantId()}, so a tenant
 * id never leaks between requests sharing a thread (servlet thread pools,
 * scheduled tasks, etc.).</p>
 *
 * <p>Two isolation mechanisms consume this context:</p>
 * <ul>
 *   <li>Hibernate {@code @Filter(name = "tenant")} on annotated entities — the
 *       application must enable the filter on the session and bind
 *       {@code :tenantId} to {@link #getTenantId()} (see {@link #applyToSession}).
 *   <li>PostgreSQL RLS policies comparing
 *       {@code tenant_id = current_setting('app.current_tenant')::uuid} — the
 *       connection must run {@code SET app.current_tenant = <tenant-id>} for
 *       the request (for example via a connection provider / init SQL).
 *   </li>
 * </ul>
 *
 * <p>This bean is also registered as Hibernate's
 * {@code hibernate.tenant_identifier_resolver} (via {@link TenantContextConfig})
 * so schema-based multi-tenancy can be switched on later by setting
 * {@code hibernate.multi_tenancy=SCHEMA}; with the default {@code NONE} the
 * resolver is never consulted and behavior is unchanged.</p>
 */
@Configuration
public class TenantContext implements CurrentTenantIdentifierResolver<String> {

    private static final ThreadLocal<String> TENANT_ID = new ThreadLocal<>();

    /** Well-known tenant id used to backfill pre-multi-tenancy rows. See docs/multi-tenancy-migration.md. */
    public static final String PLATFORM_TENANT_ID = "00000000-0000-0000-0000-000000000001";

    /** Binds the current tenant id to the calling thread. */
    public static void setTenantId(String tenantId) {
        TENANT_ID.set(tenantId);
    }

    /** Returns the tenant id bound to the calling thread, or {@code null}. */
    public static String getTenantId() {
        return TENANT_ID.get();
    }

    /** Removes the tenant id bound to the calling thread. Always call in a {@code finally} block. */
    public static void clearTenantId() {
        TENANT_ID.remove();
    }

    /** Returns {@code true} when a tenant id is bound to the calling thread. */
    public static boolean hasTenantId() {
        return getTenantId() != null;
    }

    /**
     * Enables the {@code tenant} Hibernate filter on the given session and binds
     * it to the current tenant id. Call at the start of a unit of work while
     * {@link #setTenantId} is bound; disable the filter when done.
     */
    public static void applyToSession(SessionImplementor session) {
        session.enableFilter("tenant").setParameter("tenantId", getTenantId());
    }

    @Override
    public String resolveCurrentTenantIdentifier() {
        return getTenantId();
    }

    @Override
    public boolean validateExistingCurrentSessions() {
        return hasTenantId();
    }

    /**
     * Registers this context as Hibernate's current tenant identifier resolver.
     * Active for every service that component-scans {@code com.bhukkad.common}
     * (identity, admin). With {@code hibernate.multi_tenancy=NONE} (the
     * default) Hibernate never invokes the resolver, so existing behavior is
     * preserved.
     */
    @Bean
    public HibernatePropertiesCustomizer tenantIdentifierResolver() {
        return properties -> properties.putIfAbsent(
                "hibernate.tenant_identifier_resolver", this);
    }
}

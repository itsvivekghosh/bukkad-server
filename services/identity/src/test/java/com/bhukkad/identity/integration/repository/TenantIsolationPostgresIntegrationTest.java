package com.bhukkad.identity.integration.repository;

import com.bhukkad.identity.domain.entity.Customer;
import com.bhukkad.identity.domain.repository.CustomerRepository;
import com.bhukkad.identity.support.AbstractIdentityPostgresTest;
import jakarta.persistence.EntityManager;
import javax.sql.DataSource;
import org.hibernate.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates multi-tenancy data isolation for the identity service:
 * Hibernate {@code tenant} filter and PostgreSQL RLS policy on {@code customers}.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class TenantIsolationPostgresIntegrationTest extends AbstractIdentityPostgresTest {

    private static final UUID TENANT_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TENANT_B = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM addresses");
        jdbcTemplate.update("DELETE FROM customers");
    }

    private void insertCustomer(String email, UUID tenantId, String fullName) {
        jdbcTemplate.update("""
            INSERT INTO customers (email, full_name, password_hash, is_active, email_verified,
                                   loyalty_points, wallet_balance, created_at, updated_at, tenant_id)
            VALUES (?, ?, 'hash', true, false, 0, 0, now(), now(), ?::uuid)""",
            email, fullName, tenantId.toString());
    }

    @Test
    void withoutTenantFilterAllRowsAreVisible() {
        insertCustomer("alice@example.com", TENANT_A, "Alice");
        insertCustomer("betty@example.com", TENANT_B, "Betty");

        assertThat(customerRepository.findAll()).hasSize(2);
    }

    @Test
    void hibernateTenantFilterHidesCrossTenantRows() {
        insertCustomer("alice@example.com", TENANT_A, "Alice");
        insertCustomer("betty@example.com", TENANT_B, "Betty");

        new TransactionTemplate(transactionManager).execute(status -> {
            Session session = entityManager.unwrap(Session.class);
            try {
                session.enableFilter("tenant").setParameter("tenantId", TENANT_A);

                List<Customer> customers = customerRepository.findAll();
                assertThat(customers).hasSize(1);
                assertThat(customers.get(0).getEmail()).isEqualTo("alice@example.com");
                assertThat(customers.get(0).getTenantId()).isEqualTo(TENANT_A);
            } finally {
                session.disableFilter("tenant");
            }
            return null;
        });
    }

    @Test
    void rlsPolicyHidesCrossTenantRows() throws Exception {
        insertCustomer("alice@example.com", TENANT_A, "Alice");
        insertCustomer("betty@example.com", TENANT_B, "Betty");

        // Force RLS on the table-owner connection for this test only; the DDL
        // is rolled back at the end so the migration-owned schema is untouched.
        try (Connection conn = DataSourceUtils.getConnection(dataSource)) {
            conn.setAutoCommit(false);
            try {
                try (Statement st = conn.createStatement()) {
                    st.execute("ALTER TABLE public.customers FORCE ROW LEVEL SECURITY");
                    st.execute("DROP POLICY IF EXISTS tenant_isolation ON public.customers");
                    st.execute("""
                        CREATE POLICY tenant_isolation ON public.customers
                        USING (tenant_id = current_setting('app.current_tenant', true)::uuid)""");
                }

                // No tenant set on the connection -> deny by default.
                assertCustomerCount(conn, 0);

                setCurrentTenant(conn, TENANT_A);
                assertCustomerCount(conn, 1);
                assertAllCustomersBelongTo(conn, TENANT_A);

                setCurrentTenant(conn, TENANT_B);
                assertCustomerCount(conn, 1);
                assertAllCustomersBelongTo(conn, TENANT_B);
            } finally {
                conn.rollback();
            }
        }
    }

    private void setCurrentTenant(Connection conn, UUID tenantId) throws Exception {
        try (Statement st = conn.createStatement()) {
            st.execute("SELECT set_config('app.current_tenant', '" + tenantId + "', true)");
        }
    }

    private void assertCustomerCount(Connection conn, long expected) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM public.customers")) {
            rs.next();
            assertThat(rs.getLong(1)).isEqualTo(expected);
        }
    }

    private void assertAllCustomersBelongTo(Connection conn, UUID tenantId) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT tenant_id FROM public.customers")) {
            while (rs.next()) {
                assertThat(UUID.fromString(rs.getString("tenant_id"))).isEqualTo(tenantId);
            }
        }
    }
}

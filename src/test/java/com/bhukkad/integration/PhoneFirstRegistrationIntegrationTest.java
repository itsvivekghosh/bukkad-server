package com.bhukkad.integration;

import com.bhukkad.entity.Customer;
import com.bhukkad.repository.CustomerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the phone-first registration schema (V58) and the
 * role-segregated account tables (V62): phone-first customers live on the
 * self-contained {@code customers} table, and the identity lookup methods
 * ({@code findByPhoneNumber}, {@code findByEmailOrPhoneNumber}) resolve
 * against per-role indexes instead of a shared users table.
 *
 * <p>Shares the Testcontainers MySQL context from {@link AbstractJpaIntegrationTest}
 * to verify migrations apply cleanly and entity mappings align with the schema.</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class PhoneFirstRegistrationIntegrationTest extends AbstractJpaIntegrationTest {

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanUsers() {
        jdbcTemplate.update("DELETE FROM customers");
        jdbcTemplate.update("DELETE FROM users WHERE phone_verified = TRUE OR phone_verified IS NOT NULL");
    }

    @Test
    void v58_migration_addsPhoneVerificationColumns() throws Exception {
        Set<String> columns = new HashSet<>();
        try (Connection connection = connection()) {
            DatabaseMetaData meta = connection.getMetaData();
            try (ResultSet rs = meta.getColumns(null, null, "users", null)) {
                while (rs.next()) {
                    columns.add(rs.getString("COLUMN_NAME"));
                }
            }
        }

        assertThat(columns).contains("phone_verified", "profile_completed", "phone_verified_at");
    }

    @Test
    void v62_migration_movesCredentialsOntoCustomersTable() throws Exception {
        Set<String> customerColumns = new HashSet<>();
        Set<String> registryColumns = new HashSet<>();
        try (Connection connection = connection()) {
            DatabaseMetaData meta = connection.getMetaData();
            try (ResultSet rs = meta.getColumns(null, null, "customers", null)) {
                while (rs.next()) {
                    customerColumns.add(rs.getString("COLUMN_NAME"));
                }
            }
            try (ResultSet rs = meta.getColumns(null, null, "users", null)) {
                while (rs.next()) {
                    registryColumns.add(rs.getString("COLUMN_NAME"));
                }
            }
        }

        // Credentials + PII live on customers
        assertThat(customerColumns).contains("email", "password", "full_name", "phone_number");
        // Account state stays on the registry; credentials are gone from it
        assertThat(registryColumns).contains("role", "active", "phone_verified", "profile_completed");
        assertThat(registryColumns).doesNotContain("email", "password", "phone_number");
    }

    @Test
    void v62_migration_makesCustomerEmailNullable() throws Exception {
        try (Connection connection = connection()) {
            DatabaseMetaData meta = connection.getMetaData();
            try (ResultSet rs = meta.getColumns(null, null, "customers", "email")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("IS_NULLABLE")).isEqualTo("YES");
            }
        }
    }

    @Test
    void createPhoneFirstUser_savesWithNullEmailAndPhoneVerifiedFalse() {
        Customer customer = new Customer();
        customer.setPhoneNumber("9999999999");
        customer.setEmail(null); // phone-first: no email yet
        customer.setPassword(null); // password set later via profile completion
        customer.setFullName(null);
        customer.setRole(Customer.UserRole.CUSTOMER);
        customer.setActive(true);
        customer.setEmailVerified(false);
        customer.setPhoneVerified(false);
        customer.setProfileCompleted(false);

        Customer saved = customerRepository.saveAndFlush(customer);

        Optional<Customer> found = customerRepository.findByPhoneNumber("9999999999");
        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(saved.getId());
        assertThat(found.get().getEmail()).isNull();
        assertThat(found.get().getPhoneVerified()).isFalse();
        assertThat(found.get().getProfileCompleted()).isFalse();
    }

    @Test
    void findByPhoneNumber_existingPhone_returnsCustomer() {
        Customer customer = new Customer();
        customer.setPhoneNumber("1111111111");
        customer.setEmail("phone_1111111111@temp.bhukkad.local");
        customer.setPassword("encoded");
        customer.setFullName("Test User");
        customer.setRole(Customer.UserRole.CUSTOMER);
        customer.setActive(true);
        customer.setPhoneVerified(true);
        customerRepository.saveAndFlush(customer);

        Optional<Customer> found = customerRepository.findByPhoneNumber("1111111111");

        assertThat(found).isPresent();
        assertThat(found.get().getFullName()).isEqualTo("Test User");
        assertThat(found.get().getPhoneVerified()).isTrue();
    }

    @Test
    void findByPhoneNumber_nonExistentPhone_returnsEmpty() {
        Optional<Customer> found = customerRepository.findByPhoneNumber("0000000000");
        assertThat(found).isEmpty();
    }

    @Test
    void findByEmailOrPhoneNumber_findsByEmail() {
        Customer customer = new Customer();
        customer.setPhoneNumber("3333333333");
        customer.setEmail("findbyemail@test.com");
        customer.setPassword("encoded");
        customer.setRole(Customer.UserRole.CUSTOMER);
        customer.setActive(true);
        customer.setPhoneVerified(false);
        customerRepository.saveAndFlush(customer);

        Optional<Customer> found = customerRepository.findByEmailOrPhoneNumber("findbyemail@test.com", "nonexistent");

        assertThat(found).isPresent();
        assertThat(found.get().getEmail()).isEqualTo("findbyemail@test.com");
    }

    @Test
    void findByEmailOrPhoneNumber_findsByPhone() {
        Customer customer = new Customer();
        customer.setPhoneNumber("4444444444");
        customer.setEmail("phone_4444444444@temp.bhukkad.local");
        customer.setPassword(null);
        customer.setRole(Customer.UserRole.CUSTOMER);
        customer.setActive(true);
        customer.setPhoneVerified(false);
        customerRepository.saveAndFlush(customer);

        Optional<Customer> found = customerRepository.findByEmailOrPhoneNumber("not-an-email", "4444444444");

        assertThat(found).isPresent();
        assertThat(found.get().getPhoneNumber()).isEqualTo("4444444444");
    }

    @Test
    void findByEmailOrPhoneNumber_nonExistent_returnsEmpty() {
        Optional<Customer> found = customerRepository.findByEmailOrPhoneNumber("no@no.com", "0000000000");
        assertThat(found).isEmpty();
    }

    @Test
    void updateProfileCompleted_setsColumns() {
        Customer customer = new Customer();
        customer.setPhoneNumber("2222222222");
        customer.setEmail(null);
        customer.setFullName(null);
        customer.setPassword(null);
        customer.setRole(Customer.UserRole.CUSTOMER);
        customer.setActive(true);
        customer.setPhoneVerified(true);
        customer.setProfileCompleted(false);
        Customer saved = customerRepository.saveAndFlush(customer);

        saved.setEmail("user@example.com");
        saved.setFullName("Completed User");
        saved.setPassword("encoded_pw");
        saved.setProfileCompleted(true);
        customerRepository.saveAndFlush(saved);

        Optional<Customer> fetched = customerRepository.findById(saved.getId());
        assertThat(fetched).isPresent();
        assertThat(fetched.get().getEmail()).isEqualTo("user@example.com");
        assertThat(fetched.get().getFullName()).isEqualTo("Completed User");
        assertThat(fetched.get().getProfileCompleted()).isTrue();
    }

    private Connection connection() throws Exception {
        return dataSource.getConnection();
    }
}

package com.bhukkad.identity.integration.repository;

import com.bhukkad.identity.domain.entity.Address;
import com.bhukkad.identity.domain.entity.Customer;
import com.bhukkad.identity.support.AbstractIdentityPostgresTest;

import com.bhukkad.identity.domain.entity.Address;
import com.bhukkad.identity.domain.repository.AddressRepository;
import com.bhukkad.identity.domain.entity.Customer;
import com.bhukkad.identity.domain.repository.CustomerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Validates the V2 identity migration and repositories against PostgreSQL.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class IdentityRepositoryPostgresIntegrationTest extends AbstractIdentityPostgresTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private AddressRepository addressRepository;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM addresses");
        jdbcTemplate.update("DELETE FROM customers");
    }

    @Test
    void migration_appliedBothV1AndV2() {
        Integer outboxTables = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema = current_schema() " +
                        "AND table_name IN ('outbox_events','idempotency_records')",
                Integer.class);
        assertThat(outboxTables).isEqualTo(2);

        Integer identityTables = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema = current_schema() " +
                        "AND table_name IN ('customers','addresses')",
                Integer.class);
        assertThat(identityTables).isEqualTo(2);
    }

    @Test
    void saveAndFindCustomer() {
        Customer c = new Customer();
        c.setEmail("a@b.com");
        c.setFullName("Alice");
        c.setPasswordHash("hash");
        c.setIsActive(true);
        customerRepository.saveAndFlush(c);

        var found = customerRepository.findByEmailAndIsActiveTrue("a@b.com");
        assertThat(found).isPresent();
        assertThat(found.get().getFullName()).isEqualTo("Alice");
    }

    @Test
    void duplicateEmail_throwsConstraintViolation() {
        Customer c1 = new Customer();
        c1.setEmail("dup@b.com");
        c1.setFullName("A");
        c1.setPasswordHash("h");
        c1.setIsActive(true);
        customerRepository.saveAndFlush(c1);

        Customer c2 = new Customer();
        c2.setEmail("dup@b.com");
        c2.setFullName("B");
        c2.setPasswordHash("h");
        c2.setIsActive(true);

        assertThatThrownBy(() -> customerRepository.saveAndFlush(c2))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deleteCustomer_cascadesToAddresses() {
        Customer c = new Customer();
        c.setEmail("c@d.com");
        c.setFullName("C");
        c.setPasswordHash("h");
        c.setIsActive(true);
        Customer saved = customerRepository.saveAndFlush(c);

        Address a = new Address();
        a.setCustomerId(saved.getId());
        a.setLine1("123 Main");
        a.setCity("C");
        a.setLatitude(0.0);
        a.setLongitude(0.0);
        addressRepository.saveAndFlush(a);

        jdbcTemplate.update("DELETE FROM customers WHERE id = ?", saved.getId());

        Integer remaining = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM addresses WHERE customer_id = ?", Integer.class, saved.getId());
        assertThat(remaining).isZero();
    }
}
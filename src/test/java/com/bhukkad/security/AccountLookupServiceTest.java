package com.bhukkad.security;

import com.bhukkad.entity.Admin;
import com.bhukkad.entity.Customer;
import com.bhukkad.entity.DeliveryAgent;
import com.bhukkad.entity.RestaurantOwner;
import com.bhukkad.repository.AdminRepository;
import com.bhukkad.repository.CustomerRepository;
import com.bhukkad.repository.DeliveryAgentRepository;
import com.bhukkad.repository.RestaurantOwnerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the role-routed identity resolution added by the V62 user
 * segregation: lookups must cascade through the per-role account tables
 * customer-first (the high-traffic path resolves with a single indexed
 * query), and registration uniqueness checks must keep the legacy
 * global-uniqueness semantics across all four tables.
 */
@ExtendWith(MockitoExtension.class)
class AccountLookupServiceTest {

    @Mock
    private CustomerRepository customerRepository;
    @Mock
    private RestaurantOwnerRepository restaurantOwnerRepository;
    @Mock
    private DeliveryAgentRepository deliveryAgentRepository;
    @Mock
    private AdminRepository adminRepository;

    private AccountLookupService service;

    @BeforeEach
    void setUp() {
        service = new AccountLookupService(customerRepository, restaurantOwnerRepository,
                deliveryAgentRepository, adminRepository);
    }

    // ---------------- byEmail ----------------

    @Test
    void byEmail_foundInCustomerTable_shortCircuitsBeforeOtherRoles() {
        Customer customer = new Customer();
        customer.setEmail("c@test.com");
        when(customerRepository.findByEmail("c@test.com")).thenReturn(Optional.of(customer));

        Optional<com.bhukkad.entity.User> hit = service.byEmail("c@test.com");

        assertThat(hit).isPresent();
        assertThat(AccountFields.email(hit.get())).isEqualTo("c@test.com");
        verifyNoInteractions(restaurantOwnerRepository, deliveryAgentRepository, adminRepository);
    }

    @Test
    void byEmail_missingInCustomerTable_cascadesToAdmin() {
        when(customerRepository.findByEmail("a@test.com")).thenReturn(Optional.empty());
        when(restaurantOwnerRepository.findByEmail("a@test.com")).thenReturn(Optional.empty());
        when(deliveryAgentRepository.findByEmail("a@test.com")).thenReturn(Optional.empty());
        Admin admin = new Admin();
        admin.setEmail("a@test.com");
        admin.setRole(com.bhukkad.entity.User.UserRole.ADMIN);
        when(adminRepository.findByEmail("a@test.com")).thenReturn(Optional.of(admin));

        Optional<com.bhukkad.entity.User> hit = service.byEmail("a@test.com");

        assertThat(hit).isPresent();
        assertThat(hit.get().getRole()).isEqualTo(com.bhukkad.entity.User.UserRole.ADMIN);
    }

    @Test
    void byEmail_nullOrBlank_returnsEmptyWithoutQueries() {
        assertThat(service.byEmail(null)).isEmpty();
        assertThat(service.byEmail("  ")).isEmpty();
        verifyNoInteractions(customerRepository, restaurantOwnerRepository, deliveryAgentRepository, adminRepository);
    }

    // ---------------- byPhoneNumber ----------------

    @Test
    void byPhoneNumber_foundInDeliveryAgentTable() {
        when(customerRepository.findByPhoneNumber("9000000001")).thenReturn(Optional.empty());
        when(restaurantOwnerRepository.findByPhoneNumber("9000000001")).thenReturn(Optional.empty());
        DeliveryAgent agent = new DeliveryAgent();
        agent.setPhoneNumber("9000000001");
        when(deliveryAgentRepository.findByPhoneNumber("9000000001")).thenReturn(Optional.of(agent));

        Optional<com.bhukkad.entity.User> hit = service.byPhoneNumber("9000000001");

        assertThat(hit).isPresent();
        assertThat(AccountFields.phoneNumber(hit.get())).isEqualTo("9000000001");
        verify(adminRepository, never()).findByPhoneNumber(org.mockito.ArgumentMatchers.anyString());
    }

    // ---------------- byIdentifier ----------------

    @Test
    void byIdentifier_matchesEmailOrPhoneOnCustomerTable() {
        Customer byPhone = new Customer();
        byPhone.setPhoneNumber("8888888888");
        when(customerRepository.findByEmailOrPhoneNumber("x@test.com", "x@test.com"))
                .thenReturn(Optional.empty());
        when(customerRepository.findByEmailOrPhoneNumber("8888888888", "8888888888"))
                .thenReturn(Optional.of(byPhone));

        assertThat(service.byIdentifier("x@test.com")).isEmpty();
        assertThat(service.byIdentifier("8888888888")).isPresent();
    }

    // ---------------- global uniqueness ----------------

    @Test
    void existsAnywhereByEmail_trueWhenAnyRoleHoldsIt() {
        when(customerRepository.existsByEmail("dup@test.com")).thenReturn(false);
        when(restaurantOwnerRepository.existsByEmail("dup@test.com")).thenReturn(true);

        assertThat(service.existsAnywhereByEmail("dup@test.com")).isTrue();
        // short-circuits: agent/admin repos are never consulted
        verifyNoInteractions(deliveryAgentRepository, adminRepository);
    }

    @Test
    void existsAnywhereByPhoneNumber_falseWhenNoRoleHoldsIt() {
        when(customerRepository.existsByPhoneNumber("7000000000")).thenReturn(false);
        when(restaurantOwnerRepository.existsByPhoneNumber("7000000000")).thenReturn(false);
        when(deliveryAgentRepository.existsByPhoneNumber("7000000000")).thenReturn(false);
        when(adminRepository.existsByPhoneNumber("7000000000")).thenReturn(false);

        assertThat(service.existsAnywhereByPhoneNumber("7000000000")).isFalse();
    }

    @Test
    void existsAnywhere_nullOrBlank_returnsFalse() {
        assertThat(service.existsAnywhereByEmail(null)).isFalse();
        assertThat(service.existsAnywhereByPhoneNumber("")).isFalse();
        verifyNoInteractions(customerRepository, restaurantOwnerRepository, deliveryAgentRepository, adminRepository);
    }

    // ---------------- typed accessors ----------------

    @Test
    void typedAccessors_routeToTheCorrectRoleRepository() {
        Customer customer = new Customer();
        when(customerRepository.findByEmail("c@test.com")).thenReturn(Optional.of(customer));
        RestaurantOwner owner = new RestaurantOwner();
        when(restaurantOwnerRepository.findByEmail("o@test.com")).thenReturn(Optional.of(owner));

        assertThat(service.customerByEmail("c@test.com")).contains(customer);
        assertThat(service.ownerByEmail("o@test.com")).contains(owner);
    }
}

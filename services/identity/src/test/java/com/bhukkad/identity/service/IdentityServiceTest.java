package com.bhukkad.identity.service;

import com.bhukkad.common.error.DuplicateRequestException;
import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.identity.domain.Customer;
import com.bhukkad.identity.domain.CustomerRepository;
import com.bhukkad.identity.security.JwtService;
import com.bhukkad.identity.security.PasswordService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IdentityServiceTest {

    @Mock
    private CustomerRepository customerRepository;
    @Mock
    private PasswordService passwordService;
    @Mock
    private JwtService jwtService;
    @Mock
    private IdentityEventPublisher eventPublisher;
    @Mock
    private EntityManager entityManager;

    @InjectMocks
    private IdentityService service;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "entityManager", entityManager);
    }

    @Test
    void register_newEmail_savesAndPublishesEvent() {
        when(customerRepository.findByEmail("a@b.com")).thenReturn(Optional.empty());
        when(customerRepository.save(any(Customer.class))).thenAnswer(inv -> {
            Customer c = inv.getArgument(0);
            c.setId(10L);
            return c;
        });
        Query nativeQuery = mock(Query.class);
        when(entityManager.createNativeQuery(any(String.class))).thenReturn(nativeQuery);
        when(nativeQuery.setParameter(any(String.class), any())).thenReturn(nativeQuery);

        Customer customer = service.register("a@b.com", "999", "Alice", "password123");

        assertThat(customer.getId()).isEqualTo(10L);
        verify(eventPublisher).customerRegistered(10L, "a@b.com", "Alice");
    }

    @Test
    void register_duplicateEmail_throws() {
        when(customerRepository.findByEmail("a@b.com")).thenReturn(Optional.of(new Customer()));

        assertThatThrownBy(() -> service.register("a@b.com", "999", "Alice", "password123"))
                .isInstanceOf(DuplicateRequestException.class)
                .hasMessageContaining("already registered");
    }

    @Test
    void login_validCredentials_issuesToken() {
        Customer customer = new Customer();
        customer.setId(5L);
        customer.setEmail("a@b.com");
        customer.setPasswordHash("$2a$10$hashed");
        customer.setIsActive(true);
        when(customerRepository.findByEmailAndIsActiveTrue("a@b.com")).thenReturn(Optional.of(customer));
        when(passwordService.matches("password123", "$2a$10$hashed")).thenReturn(true);
        when(jwtService.issue(5L, "a@b.com")).thenReturn("token-abc");

        IdentityService.LoginResult result = service.login("a@b.com", "password123");

        assertThat(result.token()).isEqualTo("token-abc");
        assertThat(result.customerId()).isEqualTo(5L);
    }

    @Test
    void login_unknownEmail_throwsNotFound() {
        when(customerRepository.findByEmailAndIsActiveTrue("a@b.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.login("a@b.com", "password123"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Invalid email or password");
    }

    @Test
    void login_wrongPassword_throwsNotFound() {
        Customer customer = new Customer();
        customer.setPasswordHash("$2a$10$hashed");
        customer.setIsActive(true);
        when(customerRepository.findByEmailAndIsActiveTrue("a@b.com")).thenReturn(Optional.of(customer));
        when(passwordService.matches("wrong", "$2a$10$hashed")).thenReturn(false);

        assertThatThrownBy(() -> service.login("a@b.com", "wrong"))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}

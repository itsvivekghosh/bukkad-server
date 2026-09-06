package com.bhukkad.identity.service;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.DuplicateRequestException;
import com.bhukkad.common.error.UnauthorizedException;
import com.bhukkad.identity.domain.Admin;
import com.bhukkad.identity.domain.Customer;
import com.bhukkad.identity.domain.CustomerRepository;
import com.bhukkad.identity.domain.UserRepository;
import com.bhukkad.identity.domain.User;
import com.bhukkad.identity.domain.AdminRepository;
import com.bhukkad.identity.referral.ReferralService;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IdentityServiceTest {

    @Mock
    private CustomerRepository customerRepository;
    @Mock
    private AdminRepository adminRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private PasswordService passwordService;
    @Mock
    private JwtService jwtService;
    @Mock
    private IdentityEventPublisher eventPublisher;
       @Mock
    private EntityManager entityManager;
    @Mock
    private ReferralService referralService;

    @InjectMocks
    private IdentityService service;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "em", entityManager);
    }

    private Query mockNativeQuery() {
        Query nativeQuery = mock(Query.class);
        when(entityManager.createNativeQuery(anyString())).thenReturn(nativeQuery);
        when(nativeQuery.setParameter(any(String.class), any())).thenReturn(nativeQuery);
        return nativeQuery;
    }

    @Test
    void register_newEmail_savesAndPublishesEvent() {
        when(customerRepository.findByEmailAndIsActiveTrue("a@b.com")).thenReturn(Optional.empty());
        when(customerRepository.saveAndFlush(any(Customer.class))).thenAnswer(inv -> {
            Customer c = inv.getArgument(0);
            c.setId(10L);
            return c;
        });
        mockNativeQuery();

        Customer customer = service.register("a@b.com", "999", "Alice", "password123", "CUSTOMER", null);

        assertThat(customer.getId()).isEqualTo(10L);
        verify(eventPublisher).customerRegistered(10L, "a@b.com", "Alice");
        verify(referralService).initializeNewCustomer(customer, null);
    }

    @Test
    void register_duplicateEmail_throws() {
        when(customerRepository.findByEmailAndIsActiveTrue("a@b.com")).thenReturn(Optional.of(new Customer()));

        assertThatThrownBy(() -> service.register("a@b.com", "999", "Alice", "password123", "CUSTOMER", null))
                .isInstanceOf(DuplicateRequestException.class)
                .hasMessageContaining("already registered");
    }

    @Test
    void register_unsupportedRole_throws() {
        assertThatThrownBy(() -> service.register("a@b.com", "999", "Alice", "password123", "SUPERADMIN", null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Unsupported self-registration role");
    }

    @Test
    void login_customerValidCredentials_issuesTokenWithRoleScope() {
        Customer customer = new Customer();
        customer.setId(5L);
        customer.setEmail("a@b.com");
        customer.setPasswordHash("$2a$10$hashed");
        customer.setIsActive(true);
        when(customerRepository.findByEmailAndIsActiveTrue("a@b.com")).thenReturn(Optional.of(customer));
        when(passwordService.matches("password123", "$2a$10$hashed")).thenReturn(true);
        User user = new User();
        user.setRole(User.UserRole.CUSTOMER);
        when(userRepository.findById(5L)).thenReturn(Optional.of(user));
        when(jwtService.issue(5L, "a@b.com", "CUSTOMER")).thenReturn("token-abc");

        IdentityService.LoginResult result = service.login("a@b.com", "password123");

        assertThat(result.token()).isEqualTo("token-abc");
        assertThat(result.customerId()).isEqualTo(5L);
        assertThat(result.role()).isEqualTo("CUSTOMER");
    }

    @Test
    void login_adminFallback_issuesAdminToken() {
        when(customerRepository.findByEmailAndIsActiveTrue("admin@b.com")).thenReturn(Optional.empty());
        Admin admin = new Admin();
        admin.setId(7L);
        admin.setEmail("admin@b.com");
        admin.setPassword("$2a$10$adminhash");
        when(adminRepository.findByEmail("admin@b.com")).thenReturn(Optional.of(admin));
        User user = new User();
        user.setActive(true);
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(passwordService.matches("Admin@123456", "$2a$10$adminhash")).thenReturn(true);
        when(jwtService.issue(7L, "admin@b.com", "admin")).thenReturn("admin-token");

        IdentityService.LoginResult result = service.login("admin@b.com", "Admin@123456");

        assertThat(result.token()).isEqualTo("admin-token");
        assertThat(result.role()).isEqualTo("ADMIN");
    }

    @Test
    void login_unknownEmail_throwsUnauthorized() {
        // 401 (not 404) for unknown email: prevents user enumeration.
        when(customerRepository.findByEmailAndIsActiveTrue("a@b.com")).thenReturn(Optional.empty());
        when(adminRepository.findByEmail("a@b.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.login("a@b.com", "password123"))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("Invalid email or password");
    }

    @Test
    void login_wrongPassword_throwsUnauthorized() {
        Customer customer = new Customer();
        customer.setPasswordHash("$2a$10$hashed");
        customer.setIsActive(true);
        when(customerRepository.findByEmailAndIsActiveTrue("a@b.com")).thenReturn(Optional.of(customer));
        when(passwordService.matches("wrong", "$2a$10$hashed")).thenReturn(false);

        assertThatThrownBy(() -> service.login("a@b.com", "wrong"))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void login_inactiveAdmin_throwsUnauthorized() {
        when(customerRepository.findByEmailAndIsActiveTrue("admin@b.com")).thenReturn(Optional.empty());
        Admin admin = new Admin();
        admin.setId(7L);
        admin.setPassword("$2a$10$adminhash");
        when(adminRepository.findByEmail("admin@b.com")).thenReturn(Optional.of(admin));
        User inactive = new User();
        inactive.setActive(false);
        when(userRepository.findById(7L)).thenReturn(Optional.of(inactive));

        assertThatThrownBy(() -> service.login("admin@b.com", "Admin@123456"))
                .isInstanceOf(UnauthorizedException.class);
    }
}

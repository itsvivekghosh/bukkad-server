package com.bhukkad.identity.security;

import com.bhukkad.identity.domain.Customer;
import com.bhukkad.identity.domain.CustomerRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomUserDetailsServiceTest {

    @Mock
    private CustomerRepository customerRepository;

    @InjectMocks
    private CustomUserDetailsService service;

    @Test
    void loadUserByUsername_existingCustomer_returnsUserDetails() {
        Customer customer = new Customer();
        customer.setEmail("a@b.com");
        customer.setPasswordHash("$2a$10$hashed");
        customer.setIsActive(true);
        when(customerRepository.findByEmail("a@b.com")).thenReturn(Optional.of(customer));

        UserDetails details = service.loadUserByUsername("a@b.com");

        assertThat(details.getUsername()).isEqualTo("a@b.com");
        assertThat(details.getPassword()).isEqualTo("$2a$10$hashed");
        assertThat(details.isEnabled()).isTrue();
        assertThat(details.getAuthorities()).hasSize(1);
        assertThat(details.getAuthorities().iterator().next().getAuthority()).isEqualTo("ROLE_CUSTOMER");
    }

    @Test
    void loadUserByUsername_unknownEmail_throws() {
        when(customerRepository.findByEmail("a@b.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.loadUserByUsername("a@b.com"))
                .isInstanceOf(UsernameNotFoundException.class);
    }
}

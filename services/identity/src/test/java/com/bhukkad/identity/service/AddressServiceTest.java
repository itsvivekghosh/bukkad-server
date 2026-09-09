package com.bhukkad.identity.service;

import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.identity.domain.Address;
import com.bhukkad.identity.domain.AddressRepository;
import com.bhukkad.identity.domain.CustomerRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.returnsFirstArg;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AddressServiceTest {

    @Mock
    private CustomerRepository customerRepository;
    @Mock
    private AddressRepository addressRepository;
    @Mock
    private IdentityEventPublisher eventPublisher;

    @InjectMocks
    private AddressService service;

    @Test
    void addAddress_savesAndPublishes() {
        when(customerRepository.existsById(1L)).thenReturn(true);
        when(addressRepository.save(any(Address.class))).then(returnsFirstArg());

        service.addAddress(1L, new AddressService.AddressInput("Home", "123 Main St", "City", "State", "12345", true));

        verify(eventPublisher).addressChanged(1L, null);
        verify(addressRepository).save(any(Address.class));
    }

    @Test
    void addAddress_unknownCustomer_throws() {
        when(customerRepository.existsById(99L)).thenReturn(false);

        assertThatThrownBy(() -> service.addAddress(99L, new AddressService.AddressInput("H", "l", "c", "s", "z", false)))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("99");
    }

    @Test
    void listAddresses_returnsAddresses() {
        when(addressRepository.findByCustomerId(1L)).thenReturn(List.of(new Address()));

        List<Address> result = service.listAddresses(1L);

        assertThat(result).hasSize(1);
    }

    @Test
    void addAddress_clearsExistingDefaultBeforeSettingNewDefault() {
        when(customerRepository.existsById(1L)).thenReturn(true);
        Address existing = new Address();
        existing.setIsDefault(true);
        when(addressRepository.findByCustomerId(1L)).thenReturn(List.of(existing));
        when(addressRepository.save(any(Address.class))).then(returnsFirstArg());

        service.addAddress(1L, new AddressService.AddressInput("New", "456 Oak", "Town", "ST", "99999", true));

        assertThat(existing.getIsDefault()).isFalse();
        verify(eventPublisher).addressChanged(1L, null);
    }
}
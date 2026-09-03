package com.bhukkad.serviceImpl;

import com.bhukkad.dto.request.NotificationPreferenceRequest;
import com.bhukkad.dto.response.NotificationPreferenceResponse;
import com.bhukkad.entity.Customer;
import com.bhukkad.entity.CustomerNotificationPreference;
import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.repository.CustomerNotificationPreferenceRepository;
import com.bhukkad.repository.CustomerRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationPreferenceServiceImplTest {

    private static final Long CUSTOMER_ID = 7L;

    @Mock
    private CustomerNotificationPreferenceRepository preferenceRepository;
    @Mock
    private CustomerRepository customerRepository;

    @InjectMocks
    private NotificationPreferenceServiceImpl service;

    private CustomerNotificationPreference allOn() {
        CustomerNotificationPreference pref = new CustomerNotificationPreference();
        pref.setCustomerId(CUSTOMER_ID);
        pref.setEmailEnabled(true);
        pref.setSmsEnabled(true);
        pref.setPushEnabled(true);
        pref.setWhatsappEnabled(true);
        pref.setOrderUpdatesEnabled(true);
        pref.setPromotionsEnabled(true);
        return pref;
    }

    private CustomerNotificationPreference allOff() {
        CustomerNotificationPreference pref = new CustomerNotificationPreference();
        pref.setCustomerId(CUSTOMER_ID);
        pref.setEmailEnabled(false);
        pref.setSmsEnabled(false);
        pref.setPushEnabled(false);
        pref.setWhatsappEnabled(false);
        pref.setOrderUpdatesEnabled(false);
        pref.setPromotionsEnabled(false);
        return pref;
    }

    @Test
    void getPreferences_existing_mapsValues() {
        CustomerNotificationPreference pref = allOn();
        when(preferenceRepository.findById(CUSTOMER_ID)).thenReturn(Optional.of(pref));

        NotificationPreferenceResponse response = service.getPreferences(CUSTOMER_ID);

        assertTrue(response.getEmailEnabled());
        assertTrue(response.getSmsEnabled());
        assertTrue(response.getPushEnabled());
        assertTrue(response.getWhatsappEnabled());
        assertTrue(response.getOrderUpdatesEnabled());
        assertTrue(response.getPromotionsEnabled());
    }

    @Test
    void getPreferences_missing_returnsAllEnabledDefaults() {
        when(preferenceRepository.findById(CUSTOMER_ID)).thenReturn(Optional.empty());

        NotificationPreferenceResponse response = service.getPreferences(CUSTOMER_ID);

        assertTrue(response.getEmailEnabled());
        assertTrue(response.getSmsEnabled());
        assertTrue(response.getPushEnabled());
        assertTrue(response.getWhatsappEnabled());
        assertTrue(response.getOrderUpdatesEnabled());
        assertTrue(response.getPromotionsEnabled());
    }

    @Test
    void updatePreferences_existing_appliesOnlyNonNullFields() {
        CustomerNotificationPreference pref = allOn();
        when(preferenceRepository.findById(CUSTOMER_ID)).thenReturn(Optional.of(pref));
        when(preferenceRepository.save(any(CustomerNotificationPreference.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        NotificationPreferenceRequest request = new NotificationPreferenceRequest();
        request.setEmailEnabled(false);
        request.setOrderUpdatesEnabled(false);

        NotificationPreferenceResponse response = service.updatePreferences(CUSTOMER_ID, request);

        assertFalse(response.getEmailEnabled());
        assertFalse(response.getOrderUpdatesEnabled());
        assertTrue(response.getSmsEnabled());
        assertTrue(response.getPushEnabled());
        assertTrue(response.getWhatsappEnabled());
        assertTrue(response.getPromotionsEnabled());
    }

    @Test
    void updatePreferences_existing_nullRequestFields_unchanged() {
        CustomerNotificationPreference pref = allOn();
        when(preferenceRepository.findById(CUSTOMER_ID)).thenReturn(Optional.of(pref));
        when(preferenceRepository.save(any(CustomerNotificationPreference.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        NotificationPreferenceRequest request = new NotificationPreferenceRequest();

        NotificationPreferenceResponse response = service.updatePreferences(CUSTOMER_ID, request);

        assertTrue(response.getEmailEnabled());
        assertTrue(response.getSmsEnabled());
        assertTrue(response.getPushEnabled());
        assertTrue(response.getWhatsappEnabled());
        assertTrue(response.getOrderUpdatesEnabled());
        assertTrue(response.getPromotionsEnabled());
        verify(preferenceRepository, times(1)).save(any(CustomerNotificationPreference.class));
    }

    @Test
    void updatePreferences_existing_allChannelsOff() {
        CustomerNotificationPreference pref = allOn();
        when(preferenceRepository.findById(CUSTOMER_ID)).thenReturn(Optional.of(pref));
        when(preferenceRepository.save(any(CustomerNotificationPreference.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        NotificationPreferenceRequest request = new NotificationPreferenceRequest();
        request.setEmailEnabled(false);
        request.setSmsEnabled(false);
        request.setPushEnabled(false);
        request.setWhatsappEnabled(false);
        request.setOrderUpdatesEnabled(false);
        request.setPromotionsEnabled(false);

        NotificationPreferenceResponse response = service.updatePreferences(CUSTOMER_ID, request);

        assertFalse(response.getEmailEnabled());
        assertFalse(response.getSmsEnabled());
        assertFalse(response.getPushEnabled());
        assertFalse(response.getWhatsappEnabled());
        assertFalse(response.getOrderUpdatesEnabled());
        assertFalse(response.getPromotionsEnabled());
    }

    @Test
    void updatePreferences_missing_createsDefaultsAndApplies() {
        Customer customer = new Customer();
        customer.setId(CUSTOMER_ID);

        when(preferenceRepository.findById(CUSTOMER_ID)).thenReturn(Optional.empty());
        when(customerRepository.findById(CUSTOMER_ID)).thenReturn(Optional.of(customer));
        when(preferenceRepository.save(any(CustomerNotificationPreference.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        NotificationPreferenceRequest request = new NotificationPreferenceRequest();
        request.setEmailEnabled(false);
        request.setPromotionsEnabled(false);

        NotificationPreferenceResponse response = service.updatePreferences(CUSTOMER_ID, request);

        assertFalse(response.getEmailEnabled());
        assertFalse(response.getPromotionsEnabled());
        assertTrue(response.getSmsEnabled());
        assertTrue(response.getOrderUpdatesEnabled());
        verify(preferenceRepository, times(2)).save(any(CustomerNotificationPreference.class));
    }

    @Test
    void updatePreferences_missingCustomer_throws() {
        when(preferenceRepository.findById(CUSTOMER_ID)).thenReturn(Optional.empty());
        when(customerRepository.findById(CUSTOMER_ID)).thenReturn(Optional.empty());

        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                () -> service.updatePreferences(CUSTOMER_ID, new NotificationPreferenceRequest()));

        assertEquals("Customer not found", ex.getMessage());
    }

    @Test
    void isOrderUpdatesEnabled_existingPref_returnsValue() {
        when(preferenceRepository.findById(CUSTOMER_ID)).thenReturn(Optional.of(allOff()));
        assertFalse(service.isOrderUpdatesEnabled(CUSTOMER_ID));
    }

    @Test
    void isOrderUpdatesEnabled_missing_returnsTrue() {
        when(preferenceRepository.findById(CUSTOMER_ID)).thenReturn(Optional.empty());
        assertTrue(service.isOrderUpdatesEnabled(CUSTOMER_ID));
    }

    @Test
    void isEmailEnabled_existingPref_returnsValue() {
        when(preferenceRepository.findById(CUSTOMER_ID)).thenReturn(Optional.of(allOff()));
        assertFalse(service.isEmailEnabled(CUSTOMER_ID));
    }

    @Test
    void isEmailEnabled_missing_returnsTrue() {
        when(preferenceRepository.findById(CUSTOMER_ID)).thenReturn(Optional.empty());
        assertTrue(service.isEmailEnabled(CUSTOMER_ID));
    }

    @Test
    void isSmsEnabled_existingPref_returnsValue() {
        when(preferenceRepository.findById(CUSTOMER_ID)).thenReturn(Optional.of(allOff()));
        assertFalse(service.isSmsEnabled(CUSTOMER_ID));
    }

    @Test
    void isSmsEnabled_missing_returnsTrue() {
        when(preferenceRepository.findById(CUSTOMER_ID)).thenReturn(Optional.empty());
        assertTrue(service.isSmsEnabled(CUSTOMER_ID));
    }

    @Test
    void isPushEnabled_existingPref_returnsValue() {
        when(preferenceRepository.findById(CUSTOMER_ID)).thenReturn(Optional.of(allOff()));
        assertFalse(service.isPushEnabled(CUSTOMER_ID));
    }

    @Test
    void isPushEnabled_missing_returnsTrue() {
        when(preferenceRepository.findById(CUSTOMER_ID)).thenReturn(Optional.empty());
        assertTrue(service.isPushEnabled(CUSTOMER_ID));
    }

    @Test
    void isWhatsappEnabled_existingPref_returnsValue() {
        when(preferenceRepository.findById(CUSTOMER_ID)).thenReturn(Optional.of(allOff()));
        assertFalse(service.isWhatsappEnabled(CUSTOMER_ID));
    }

    @Test
    void isWhatsappEnabled_missing_returnsTrue() {
        when(preferenceRepository.findById(CUSTOMER_ID)).thenReturn(Optional.empty());
        assertTrue(service.isWhatsappEnabled(CUSTOMER_ID));
    }

    @Test
    void getPreferences_missing_doesNotCreateOrThrow() {
        when(preferenceRepository.findById(CUSTOMER_ID)).thenReturn(Optional.empty());

        NotificationPreferenceResponse response = service.getPreferences(CUSTOMER_ID);

        assertEquals(true, response.getEmailEnabled());
        verify(customerRepository, org.mockito.Mockito.never()).findById(any());
    }
}

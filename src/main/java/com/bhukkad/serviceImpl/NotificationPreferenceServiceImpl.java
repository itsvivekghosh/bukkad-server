package com.bhukkad.serviceImpl;

import com.bhukkad.dto.request.NotificationPreferenceRequest;
import com.bhukkad.dto.response.NotificationPreferenceResponse;
import com.bhukkad.entity.Customer;
import com.bhukkad.entity.CustomerNotificationPreference;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.repository.CustomerNotificationPreferenceRepository;
import com.bhukkad.repository.CustomerRepository;
import com.bhukkad.service.NotificationPreferenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class NotificationPreferenceServiceImpl implements NotificationPreferenceService {

    private final CustomerNotificationPreferenceRepository preferenceRepository;
    private final CustomerRepository customerRepository;

    @Override
    @Transactional(readOnly = true)
    public NotificationPreferenceResponse getPreferences(Long customerId) {
        return preferenceRepository.findById(customerId)
                .map(this::toResponse)
                .orElseGet(this::defaultResponse);
    }

    @Override
    @Transactional
    public NotificationPreferenceResponse updatePreferences(Long customerId, NotificationPreferenceRequest request) {
        CustomerNotificationPreference pref = preferenceRepository.findById(customerId)
                .orElseGet(() -> createDefault(customerId));
        if (request.getEmailEnabled() != null) {
            pref.setEmailEnabled(request.getEmailEnabled());
        }
        if (request.getSmsEnabled() != null) {
            pref.setSmsEnabled(request.getSmsEnabled());
        }
        if (request.getPushEnabled() != null) {
            pref.setPushEnabled(request.getPushEnabled());
        }
        if (request.getWhatsappEnabled() != null) {
            pref.setWhatsappEnabled(request.getWhatsappEnabled());
        }
        if (request.getOrderUpdatesEnabled() != null) {
            pref.setOrderUpdatesEnabled(request.getOrderUpdatesEnabled());
        }
        if (request.getPromotionsEnabled() != null) {
            pref.setPromotionsEnabled(request.getPromotionsEnabled());
        }
        return toResponse(preferenceRepository.save(pref));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isOrderUpdatesEnabled(Long customerId) {
        return preferenceRepository.findById(customerId)
                .map(CustomerNotificationPreference::getOrderUpdatesEnabled)
                .orElse(true);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isEmailEnabled(Long customerId) {
        return preferenceRepository.findById(customerId)
                .map(CustomerNotificationPreference::getEmailEnabled)
                .orElse(true);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isSmsEnabled(Long customerId) {
        return preferenceRepository.findById(customerId)
                .map(CustomerNotificationPreference::getSmsEnabled)
                .orElse(true);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isPushEnabled(Long customerId) {
        return preferenceRepository.findById(customerId)
                .map(CustomerNotificationPreference::getPushEnabled)
                .orElse(true);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isWhatsappEnabled(Long customerId) {
        return preferenceRepository.findById(customerId)
                .map(CustomerNotificationPreference::getWhatsappEnabled)
                .orElse(true);
    }

    private CustomerNotificationPreference createDefault(Long customerId) {
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found"));
        return preferenceRepository.save(CustomerNotificationPreference.defaults(customer));
    }

    private NotificationPreferenceResponse defaultResponse() {
        return NotificationPreferenceResponse.builder()
                .emailEnabled(true)
                .smsEnabled(true)
                .pushEnabled(true)
                .whatsappEnabled(true)
                .orderUpdatesEnabled(true)
                .promotionsEnabled(true)
                .build();
    }

    private NotificationPreferenceResponse toResponse(CustomerNotificationPreference pref) {
        return NotificationPreferenceResponse.builder()
                .emailEnabled(pref.getEmailEnabled())
                .smsEnabled(pref.getSmsEnabled())
                .pushEnabled(pref.getPushEnabled())
                .whatsappEnabled(pref.getWhatsappEnabled())
                .orderUpdatesEnabled(pref.getOrderUpdatesEnabled())
                .promotionsEnabled(pref.getPromotionsEnabled())
                .build();
    }
}

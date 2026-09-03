package com.bhukkad.compliance;

import com.bhukkad.audit.Audited;
import com.bhukkad.entity.Address;
import com.bhukkad.entity.Order;
import com.bhukkad.entity.User;
import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.repository.AddressRepository;
import com.bhukkad.repository.OrderRepository;
import com.bhukkad.repository.UserRepository;
import com.bhukkad.security.AccountFields;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class DataExportService {

    private static final String COL_CREATED_AT = "createdAt";


    private final DataExportRequestRepository dataExportRequestRepository;
    private final UserRepository userRepository;
    private final OrderRepository orderRepository;
    private final AddressRepository addressRepository;
    private final ConsentRecordRepository consentRecordRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    @Audited(action = "DATA_EXPORT_REQUESTED", resourceType = "USER", resourceId = "#userId")
    public DataExportRequest createRequest(Long userId) {
        DataExportRequest request = new DataExportRequest();
        request.setUserId(userId);
        request.setRequestedAt(LocalDateTime.now());
        request.setStatus(DataExportRequest.Status.REQUESTED);
        return dataExportRequestRepository.save(request);
    }

    @Transactional
    public DataExportRequest processRequest(Long requestId) {
        DataExportRequest request = dataExportRequestRepository.findById(requestId)
                .orElseThrow(() -> new ResourceNotFoundException("Data export request not found: " + requestId));
        try {
            Map<String, Object> payload = buildExportPayload(request.getUserId());
            request.setPayloadJson(objectMapper.writeValueAsString(payload));
            request.setStatus(DataExportRequest.Status.READY);
            request.setCompletedAt(LocalDateTime.now());
        } catch (JsonProcessingException e) {
            request.setStatus(DataExportRequest.Status.FAILED);
            request.setCompletedAt(LocalDateTime.now());
            dataExportRequestRepository.save(request);
            log.error("Failed to serialize data export | requestId={} | userId={}",
                    requestId, request.getUserId(), e);
            throw new BusinessException("Failed to generate data export", e);
        }
        return dataExportRequestRepository.save(request);
    }

    @Transactional(readOnly = true)
    public String getExport(Long userId) {
        DataExportRequest request = dataExportRequestRepository
                .findTopByUserIdAndStatusOrderByCompletedAtDesc(userId, DataExportRequest.Status.READY)
                .orElseThrow(() -> new BusinessException("No ready data export found"));
        return request.getPayloadJson();
    }

    private Map<String, Object> buildExportPayload(Long userId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", userId);
        payload.put("exportedAt", LocalDateTime.now().toString());

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("id", user.getId());
        profile.put("email", AccountFields.email(user));
        profile.put("fullName", AccountFields.fullName(user));
        profile.put("phoneNumber", AccountFields.phoneNumber(user));
        profile.put("role", user.getRole() != null ? user.getRole().name() : null);
        profile.put("active", user.getActive());
        profile.put("emailVerified", user.getEmailVerified());
        profile.put(COL_CREATED_AT, user.getCreatedAt() != null ? user.getCreatedAt().toString() : null);
        payload.put("profile", profile);

        List<Map<String, Object>> orders = new ArrayList<>();
        for (Order order : orderRepository.findByCustomerIdWithDetails(userId)) {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("id", order.getId());
            summary.put("orderNumber", order.getOrderNumber());
            summary.put("status", order.getStatus() != null ? order.getStatus().name() : null);
            summary.put("totalAmount", order.getTotalAmount());
            summary.put(COL_CREATED_AT, order.getCreatedAt() != null ? order.getCreatedAt().toString() : null);
            orders.add(summary);
        }
        payload.put("orders", orders);

        List<Map<String, Object>> addresses = new ArrayList<>();
        for (Address address : addressRepository.findByCustomerId(userId)) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", address.getId());
            entry.put("addressLine1", address.getAddressLine1());
            entry.put("addressLine2", address.getAddressLine2());
            entry.put("city", address.getCity());
            entry.put("state", address.getState());
            entry.put("pincode", address.getPincode());
            entry.put("label", address.getLabel());
            entry.put("type", address.getType() != null ? address.getType().name() : null);
            entry.put("isDefault", address.getIsDefault());
            addresses.add(entry);
        }
        payload.put("addresses", addresses);

        List<Map<String, Object>> consents = new ArrayList<>();
        for (ConsentRecord consent : consentRecordRepository.findByUserId(userId)) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("purpose", consent.getPurpose());
            entry.put("granted", consent.getGranted());
            entry.put("source", consent.getSource());
            entry.put(COL_CREATED_AT, consent.getCreatedAt() != null ? consent.getCreatedAt().toString() : null);
            consents.add(entry);
        }
        payload.put("consents", consents);

        return payload;
    }
}

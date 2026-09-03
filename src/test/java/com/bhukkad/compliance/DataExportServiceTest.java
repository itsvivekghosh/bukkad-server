package com.bhukkad.compliance;

import com.bhukkad.entity.Address;
import com.bhukkad.entity.Order;
import com.bhukkad.entity.Customer;
import com.bhukkad.entity.User;
import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.repository.AddressRepository;
import com.bhukkad.repository.OrderRepository;
import com.bhukkad.repository.UserRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DataExportServiceTest {

    @Mock private DataExportRequestRepository dataExportRequestRepository;
    @Mock private UserRepository userRepository;
    @Mock private OrderRepository orderRepository;
    @Mock private AddressRepository addressRepository;
    @Mock private ConsentRecordRepository consentRecordRepository;

    private DataExportService service;

    @BeforeEach
    void setUp() {
        service = new DataExportService(dataExportRequestRepository, userRepository, orderRepository,
                addressRepository, consentRecordRepository, new ObjectMapper());
    }

    @Test
    void createRequest_savesWithRequestedStatus() {
        when(dataExportRequestRepository.save(any(DataExportRequest.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        DataExportRequest result = service.createRequest(42L);

        assertEquals(42L, result.getUserId());
        assertEquals(DataExportRequest.Status.REQUESTED, result.getStatus());
        assertNotNull(result.getRequestedAt());
        verify(dataExportRequestRepository).save(any(DataExportRequest.class));
    }

    @Test
    void processRequest_notFound_throwsResourceNotFound() {
        when(dataExportRequestRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.processRequest(99L));
    }

    @Test
    void processRequest_buildsPayloadAndMarksReady() {
        DataExportRequest request = new DataExportRequest();
        request.setId(1L);
        request.setUserId(5L);

        Customer user = new Customer();
        user.setId(5L);
        user.setEmail("a@b.com");
        user.setFullName("Ada");
        user.setPhoneNumber("123");
        user.setRole(User.UserRole.CUSTOMER);
        user.setActive(true);
        user.setEmailVerified(true);
        user.setCreatedAt(LocalDateTime.now());

        Order order = new Order();
        order.setId(10L);
        order.setOrderNumber("ORD-1");
        order.setStatus(Order.OrderStatus.PLACED);
        order.setTotalAmount(99.9);
        order.setCreatedAt(LocalDateTime.now());

        Address address = new Address();
        address.setId(20L);
        address.setAddressLine1("1 Main");
        address.setCity("Blr");
        address.setState("KA");
        address.setPincode("560001");
        address.setType(com.bhukkad.entity.Address.AddressType.HOME);
        address.setIsDefault(true);

        ConsentRecord consent = new ConsentRecord();
        consent.setPurpose("MARKETING");
        consent.setGranted(true);
        consent.setSource("api");

        when(dataExportRequestRepository.findById(1L)).thenReturn(Optional.of(request));
        when(userRepository.findById(5L)).thenReturn(Optional.of(user));
        when(orderRepository.findByCustomerIdWithDetails(5L)).thenReturn(List.of(order));
        when(addressRepository.findByCustomerId(5L)).thenReturn(List.of(address));
        when(consentRecordRepository.findByUserId(5L)).thenReturn(List.of(consent));
        when(dataExportRequestRepository.save(any(DataExportRequest.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        DataExportRequest result = service.processRequest(1L);

        assertEquals(DataExportRequest.Status.READY, result.getStatus());
        assertNotNull(result.getCompletedAt());
        assertNotNull(result.getPayloadJson());
        assertTrue(result.getPayloadJson().contains("\"orders\""));
        assertTrue(result.getPayloadJson().contains("\"addresses\""));
        assertTrue(result.getPayloadJson().contains("\"consents\""));
        assertTrue(result.getPayloadJson().contains("\"profile\""));
    }

    @Test
    void processRequest_serializationFailure_marksFailedAndThrows() throws Exception {
        DataExportRequest request = new DataExportRequest();
        request.setId(1L);
        request.setUserId(5L);

        ObjectMapper failing = mock(ObjectMapper.class);
        when(failing.writeValueAsString(any())).thenThrow(new JsonProcessingException("boom") {});
        service = new DataExportService(dataExportRequestRepository, userRepository, orderRepository,
                addressRepository, consentRecordRepository, failing);

        when(dataExportRequestRepository.findById(1L)).thenReturn(Optional.of(request));
        when(userRepository.findById(5L)).thenReturn(Optional.of(new User()));
        when(orderRepository.findByCustomerIdWithDetails(5L)).thenReturn(List.of());
        when(addressRepository.findByCustomerId(5L)).thenReturn(List.of());
        when(consentRecordRepository.findByUserId(5L)).thenReturn(List.of());

        BusinessException ex = assertThrows(BusinessException.class, () -> service.processRequest(1L));
        assertEquals(DataExportRequest.Status.FAILED, request.getStatus());
        assertNotNull(request.getCompletedAt());
        verify(dataExportRequestRepository).save(request);
    }

    @Test
    void processRequest_missingUser_throwsResourceNotFound() {
        DataExportRequest request = new DataExportRequest();
        request.setId(1L);
        request.setUserId(999L);
        when(dataExportRequestRepository.findById(1L)).thenReturn(Optional.of(request));
        when(userRepository.findById(999L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.processRequest(1L));
    }

    @Test
    void getExport_noReadyExport_throwsBusiness() {
        when(dataExportRequestRepository.findTopByUserIdAndStatusOrderByCompletedAtDesc(
                5L, DataExportRequest.Status.READY)).thenReturn(Optional.empty());

        assertThrows(BusinessException.class, () -> service.getExport(5L));
    }

    @Test
    void getExport_returnsPayload() {
        DataExportRequest request = new DataExportRequest();
        request.setPayloadJson("{\"userId\":5}");
        when(dataExportRequestRepository.findTopByUserIdAndStatusOrderByCompletedAtDesc(
                5L, DataExportRequest.Status.READY)).thenReturn(Optional.of(request));

        assertEquals("{\"userId\":5}", service.getExport(5L));
    }
}
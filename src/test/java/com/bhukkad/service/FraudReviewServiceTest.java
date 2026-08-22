package com.bhukkad.service;

import com.bhukkad.dto.request.FraudReviewActionRequest;
import com.bhukkad.entity.Customer;
import com.bhukkad.entity.FraudEvent;
import com.bhukkad.entity.FraudReviewAction;
import com.bhukkad.entity.User;
import com.bhukkad.exception.BusinessException;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.repository.FraudEventRepository;
import com.bhukkad.repository.FraudReviewActionRepository;
import com.bhukkad.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FraudReviewServiceTest {

    @Mock
    private FraudReviewActionRepository fraudReviewActionRepository;
    @Mock
    private FraudEventRepository fraudEventRepository;
    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private FraudReviewService service;

    private FraudEvent eventWithCustomer() {
        Customer customer = new Customer();
        customer.setId(5L);

        FraudEvent event = new FraudEvent();
        event.setId(1L);
        event.setEventType("AUTH_LOGIN");
        event.setCustomer(customer);
        return event;
    }

    @Test
    void listPending_returnsPendingActions() {
        FraudReviewAction action = new FraudReviewAction();
        action.setId(1L);
        action.setFraudEvent(eventWithCustomer());
        action.setAction(FraudReviewAction.FraudReviewActionType.IGNORE);
        action.setStatus(FraudReviewAction.FraudReviewStatus.PENDING);

        when(fraudReviewActionRepository.findByStatusOrderByCreatedAtDesc(
                FraudReviewAction.FraudReviewStatus.PENDING)).thenReturn(List.of(action));

        var result = service.listPending();

        assertEquals(1, result.size());
        assertEquals("IGNORE", result.get(0).getAction());
        assertEquals("AUTH_LOGIN", result.get(0).getEventType());
    }

    @Test
    void action_ignoreEvent_recordsDecision() {
        FraudEvent event = eventWithCustomer();
        when(fraudEventRepository.findById(1L)).thenReturn(Optional.of(event));
        when(fraudReviewActionRepository.existsByFraudEventId(1L)).thenReturn(false);

        User admin = new User();
        admin.setId(9L);
        when(userRepository.findById(9L)).thenReturn(Optional.of(admin));

        when(fraudReviewActionRepository.save(any(FraudReviewAction.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        FraudReviewActionRequest request = new FraudReviewActionRequest();
        request.setAction("IGNORE");
        request.setNotes("Looks legitimate");

        var response = service.action(1L, request, 9L);

        assertEquals("IGNORE", response.getAction());
        assertEquals("DONE", response.getStatus());
        assertEquals(9L, response.getReviewedBy());
        verify(userRepository, never()).save(any());
    }

    @Test
    void action_blockCustomer_deactivatesAccount() {
        FraudEvent event = eventWithCustomer();
        when(fraudEventRepository.findById(1L)).thenReturn(Optional.of(event));
        when(fraudReviewActionRepository.existsByFraudEventId(1L)).thenReturn(false);

        User admin = new User();
        admin.setId(9L);
        when(userRepository.findById(9L)).thenReturn(Optional.of(admin));

        Customer customer = event.getCustomer();
        customer.setActive(true);
        when(userRepository.findById(5L)).thenReturn(Optional.of(customer));

        when(fraudReviewActionRepository.save(any(FraudReviewAction.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        FraudReviewActionRequest request = new FraudReviewActionRequest();
        request.setAction("BLOCK_CUSTOMER");

        var response = service.action(1L, request, 9L);

        assertEquals("BLOCK_CUSTOMER", response.getAction());
        assertFalse(customer.getActive());
        verify(userRepository).save(customer);
    }

    @Test
    void action_unknownEvent_throwsNotFound() {
        when(fraudEventRepository.findById(1L)).thenReturn(Optional.empty());

        FraudReviewActionRequest request = new FraudReviewActionRequest();
        request.setAction("IGNORE");

        assertThrows(ResourceNotFoundException.class, () -> service.action(1L, request, 9L));
    }

    @Test
    void action_alreadyReviewed_throwsBusinessException() {
        when(fraudEventRepository.findById(1L)).thenReturn(Optional.of(eventWithCustomer()));
        when(fraudReviewActionRepository.existsByFraudEventId(1L)).thenReturn(true);

        FraudReviewActionRequest request = new FraudReviewActionRequest();
        request.setAction("IGNORE");

        assertThrows(BusinessException.class, () -> service.action(1L, request, 9L));
    }

    @Test
    void action_invalidActionType_throwsBusinessException() {
        when(fraudEventRepository.findById(1L)).thenReturn(Optional.of(eventWithCustomer()));
        when(fraudReviewActionRepository.existsByFraudEventId(1L)).thenReturn(false);

        FraudReviewActionRequest request = new FraudReviewActionRequest();
        request.setAction("HACK_THE_PLATFORM");

        assertThrows(BusinessException.class, () -> service.action(1L, request, 9L));
    }
}

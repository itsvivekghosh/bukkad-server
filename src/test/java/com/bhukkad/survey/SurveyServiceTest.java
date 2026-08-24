package com.bhukkad.survey;

import com.bhukkad.entity.Customer;
import com.bhukkad.entity.DeliverySurvey;
import com.bhukkad.entity.Order;
import com.bhukkad.entity.Order.OrderStatus;
import com.bhukkad.exception.BusinessException;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.exception.UnauthorizedException;
import com.bhukkad.repository.DeliverySurveyRepository;
import com.bhukkad.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SurveyServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private DeliverySurveyRepository surveyRepository;

    @InjectMocks
    private SurveyService service;

    private Customer customer;
    private Order deliveredOrder;

    @BeforeEach
    void setUp() {
        customer = new Customer();
        customer.setId(1L);

        deliveredOrder = new Order();
        deliveredOrder.setId(100L);
        deliveredOrder.setCustomer(customer);
        deliveredOrder.setStatus(OrderStatus.DELIVERED);
    }

    @Test
    void submitSurvey_happyPathPersistsSurvey() {
        when(orderRepository.findById(100L)).thenReturn(Optional.of(deliveredOrder));
        when(surveyRepository.findByOrderId(100L)).thenReturn(Optional.empty());
        when(surveyRepository.save(any(DeliverySurvey.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        DeliverySurvey result = service.submitSurvey(1L, 100L, 5, 4, 5, "  Great food  ");

        ArgumentCaptor<DeliverySurvey> captor = ArgumentCaptor.forClass(DeliverySurvey.class);
        verify(surveyRepository).save(captor.capture());
        DeliverySurvey saved = captor.getValue();
        assertEquals(100L, saved.getOrder().getId());
        assertEquals(customer, saved.getCustomer());
        assertEquals(5, saved.getRatingDelivery());
        assertEquals(4, saved.getRatingFood());
        assertEquals(5, saved.getRatingSpeed());
        assertEquals("Great food", saved.getComment());
        assertNotNull(saved.getSubmittedAt());
        assertNotNull(result);
    }

    @Test
    void submitSurvey_throwsWhenOrderNotFound() {
        when(orderRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.submitSurvey(1L, 99L, 5, 4, 5, null));
    }

    @Test
    void submitSurvey_throwsWhenOrderBelongsToAnotherCustomer() {
        Customer other = new Customer();
        other.setId(2L);
        deliveredOrder.setCustomer(other);
        when(orderRepository.findById(100L)).thenReturn(Optional.of(deliveredOrder));

        assertThrows(UnauthorizedException.class,
                () -> service.submitSurvey(1L, 100L, 5, 4, 5, null));
    }

    @Test
    void submitSurvey_throwsWhenOrderNotDelivered() {
        deliveredOrder.setStatus(OrderStatus.PREPARING);
        when(orderRepository.findById(100L)).thenReturn(Optional.of(deliveredOrder));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.submitSurvey(1L, 100L, 5, 4, 5, null));
        assertTrue(ex.getMessage().contains("delivered"));
    }

    @Test
    void submitSurvey_throwsWhenSurveyAlreadySubmitted() {
        when(orderRepository.findById(100L)).thenReturn(Optional.of(deliveredOrder));
        when(surveyRepository.findByOrderId(100L)).thenReturn(Optional.of(new DeliverySurvey()));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.submitSurvey(1L, 100L, 5, 4, 5, null));
        assertTrue(ex.getMessage().contains("already"));
        verify(surveyRepository, never()).save(any());
    }

    @Test
    void submitSurvey_throwsWhenRatingOutOfRange() {
        assertThrows(BusinessException.class,
                () -> service.submitSurvey(1L, 100L, 6, 4, 5, null));
        assertThrows(BusinessException.class,
                () -> service.submitSurvey(1L, 100L, 5, 0, 5, null));
        assertThrows(BusinessException.class,
                () -> service.submitSurvey(1L, 100L, 5, 4, -1, null));
        verifyNoInteractions(orderRepository);
    }

    @Test
    void submitSurvey_throwsWhenCommentTooLong() {
        String tooLong = "x".repeat(1001);
        assertThrows(BusinessException.class,
                () -> service.submitSurvey(1L, 100L, 5, 4, 5, tooLong));
        verifyNoInteractions(orderRepository);
    }
}

package com.bhukkad.order;

import com.bhukkad.config.ScheduledOrderProperties;
import com.bhukkad.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ScheduledOrderValidatorTest {

    @Mock
    private ScheduledOrderProperties properties;

    @InjectMocks
    private ScheduledOrderValidator validator;

    @BeforeEach
    void setUp() {
        lenient().when(properties.getMinimumLeadMinutes()).thenReturn(30);
        lenient().when(properties.getMaxDaysAhead()).thenReturn(7);
    }

    @Test
    void validateScheduledAt_null_returns() {
        validator.validateScheduledAt(null);
        verifyNoInteractions(properties);
    }

    @Test
    void validateScheduledAt_tooSoon_throws() {
        LocalDateTime tooSoon = LocalDateTime.now().plusMinutes(10);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> validator.validateScheduledAt(tooSoon));
        assertTrue(ex.getMessage().contains("at least 30 minutes"));
    }

    @Test
    void validateScheduledAt_tooFar_throws() {
        LocalDateTime tooFar = LocalDateTime.now().plusDays(10);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> validator.validateScheduledAt(tooFar));
        assertTrue(ex.getMessage().contains("cannot be more than 7 days"));
    }

    @Test
    void validateScheduledAt_valid_passes() {
        LocalDateTime valid = LocalDateTime.now().plusHours(2);
        validator.validateScheduledAt(valid);
        verify(properties).getMinimumLeadMinutes();
        verify(properties).getMaxDaysAhead();
    }

    @Test
    void isScheduledOrder_null_returnsFalse() {
        assertFalse(validator.isScheduledOrder(null));
    }

    @Test
    void isScheduledOrder_pastTime_returnsFalse() {
        LocalDateTime past = LocalDateTime.now().minusHours(1);
        assertFalse(validator.isScheduledOrder(past));
    }

    @Test
    void isScheduledOrder_futureTime_returnsTrue() {
        LocalDateTime future = LocalDateTime.now().plusHours(2);
        assertTrue(validator.isScheduledOrder(future));
    }
}
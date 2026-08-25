package com.bhukkad.metrics;

import com.bhukkad.dto.response.BatchOrderResponse;
import com.bhukkad.dto.response.OrderResponse;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BusinessMetricsAspectTest {

    @Mock
    private BusinessMetricsService businessMetricsService;
    @Mock
    private JoinPoint joinPoint;
    @Mock
    private MethodSignature signature;

    private BusinessMetricsAspect aspect;

    @BeforeEach
    void setUp() {
        aspect = new BusinessMetricsAspect(businessMetricsService);
        lenient().when(joinPoint.getSignature()).thenReturn(signature);
        lenient().when(signature.toShortString()).thenReturn("createOrder(..)");
    }

    @Test
    void recordOrderCreated_orderResponse_recordsTotal() {
        OrderResponse order = new OrderResponse();
        order.setTotalAmount(250.0);

        aspect.recordOrderCreated(joinPoint, order);

        verify(businessMetricsService).recordOrderCreated(250.0);
    }

    @Test
    void recordOrderCreated_orderResponseNullTotal_recordsZero() {
        OrderResponse order = new OrderResponse();
        order.setTotalAmount(null);

        aspect.recordOrderCreated(joinPoint, order);

        verify(businessMetricsService).recordOrderCreated(0.0);
    }

    @Test
    void recordOrderCreated_batchResponse_recordsSum() {
        BatchOrderResponse batch = BatchOrderResponse.builder()
                .orders(java.util.List.of(
                        OrderResponse.builder().totalAmount(100.0).build(),
                        OrderResponse.builder().totalAmount(50.0).build()))
                .build();

        aspect.recordOrderCreated(joinPoint, batch);

        verify(businessMetricsService).recordOrderCreated(150.0);
    }

    @Test
    void recordOrderCreated_batchResponseEmpty_recordsZero() {
        BatchOrderResponse batch = BatchOrderResponse.builder()
                .orders(java.util.List.of())
                .build();

        aspect.recordOrderCreated(joinPoint, batch);

        verify(businessMetricsService).recordOrderCreated(0.0);
    }

    @Test
    void recordOrderCreated_batchResponseNullOrders_recordsZero() {
        BatchOrderResponse batch = BatchOrderResponse.builder().build();

        aspect.recordOrderCreated(joinPoint, batch);

        verify(businessMetricsService).recordOrderCreated(0.0);
    }

    @Test
    void recordOrderCreated_nullResult_recordsZero() {
        aspect.recordOrderCreated(joinPoint, null);

        verify(businessMetricsService).recordOrderCreated(0.0);
    }

    @Test
    void recordOrderCreated_unknownObjectWithGetTotal_recordsValue() {
        Object result = new Object() {
            public Double getTotal() {
                return 99.0;
            }
        };

        aspect.recordOrderCreated(joinPoint, result);

        verify(businessMetricsService).recordOrderCreated(99.0);
    }

    @Test
    void recordOrderCreated_unknownObjectNoTotal_recordsZero() {
        Object result = new Object();

        aspect.recordOrderCreated(joinPoint, result);

        verify(businessMetricsService).recordOrderCreated(0.0);
    }

    @Test
    void recordOrderCreated_serviceThrows_swallowedWithWarning() {
        OrderResponse order = new OrderResponse();
        order.setTotalAmount(10.0);
        doThrow(new RuntimeException("metrics down")).when(businessMetricsService).recordOrderCreated(10.0);

        aspect.recordOrderCreated(joinPoint, order);

        verify(businessMetricsService).recordOrderCreated(10.0);
    }
}
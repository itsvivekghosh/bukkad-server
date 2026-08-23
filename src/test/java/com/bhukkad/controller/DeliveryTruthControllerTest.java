package com.bhukkad.controller;

import com.bhukkad.dto.response.ApiResponse;
import com.bhukkad.dto.response.OrderEtaDetailResponse;
import com.bhukkad.delivery.OrderEtaHistoryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeliveryTruthControllerTest {

    @Mock
    private OrderEtaHistoryService orderEtaHistoryService;

    @InjectMocks
    private DeliveryTruthController controller;

    @Test
    void getOrderEtaDetail_returnsEtaDetail() {
        OrderEtaDetailResponse detail = OrderEtaDetailResponse.builder().build();
        when(orderEtaHistoryService.getEtaDetail(42L)).thenReturn(detail);

        ResponseEntity<ApiResponse<OrderEtaDetailResponse>> response = controller.getOrderEtaDetail(42L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertSame(detail, response.getBody().getData());
        verify(orderEtaHistoryService).getEtaDetail(42L);
    }
}

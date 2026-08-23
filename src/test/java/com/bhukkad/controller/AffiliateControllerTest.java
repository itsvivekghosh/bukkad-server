package com.bhukkad.controller;

import com.bhukkad.dto.request.AffiliateCodeRequest;
import com.bhukkad.dto.response.AffiliateCodeResponse;
import com.bhukkad.dto.response.AffiliateStatsResponse;
import com.bhukkad.dto.response.ApiResponse;
import com.bhukkad.referral.AffiliateService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AffiliateControllerTest {

    @Mock
    private AffiliateService affiliateService;

    @InjectMocks
    private AffiliateController controller;

    @Test
    void list_returnsAllCodes() {
        List<AffiliateCodeResponse> codes = List.of(AffiliateCodeResponse.builder().build());
        when(affiliateService.listAll()).thenReturn(codes);

        ResponseEntity<ApiResponse<List<AffiliateCodeResponse>>> response = controller.list();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertSame(codes, response.getBody().getData());
        verify(affiliateService).listAll();
    }

    @Test
    void create_returnsCreatedCode() {
        AffiliateCodeRequest request = new AffiliateCodeRequest();
        AffiliateCodeResponse code = AffiliateCodeResponse.builder().build();
        when(affiliateService.create(request)).thenReturn(code);

        ResponseEntity<ApiResponse<AffiliateCodeResponse>> response = controller.create(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Affiliate code created", response.getBody().getMessage());
        assertSame(code, response.getBody().getData());
    }

    @Test
    void update_returnsUpdatedCode() {
        AffiliateCodeRequest request = new AffiliateCodeRequest();
        AffiliateCodeResponse code = AffiliateCodeResponse.builder().build();
        when(affiliateService.update(4L, request)).thenReturn(code);

        ResponseEntity<ApiResponse<AffiliateCodeResponse>> response = controller.update(4L, request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Affiliate code updated", response.getBody().getMessage());
        assertSame(code, response.getBody().getData());
    }

    @Test
    void deactivate_returnsSuccess() {
        ResponseEntity<ApiResponse<Void>> response = controller.deactivate(4L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Affiliate code deactivated", response.getBody().getMessage());
        verify(affiliateService).deactivate(4L);
    }

    @Test
    void stats_returnsAffiliateStats() {
        AffiliateStatsResponse stats = AffiliateStatsResponse.builder().build();
        when(affiliateService.getStats(4L)).thenReturn(stats);

        ResponseEntity<ApiResponse<AffiliateStatsResponse>> response = controller.stats(4L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertSame(stats, response.getBody().getData());
    }
}

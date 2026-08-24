package com.bhukkad.health;

import com.bhukkad.config.SyntheticHealthCheckProperties;
import com.bhukkad.logging.alert.AlertService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SyntheticHealthCheckServiceTest {

    @Mock
    private RestTemplate restTemplate;
    @Mock
    private AlertService alertService;

    private SyntheticHealthCheckService service;
    private SyntheticHealthCheckProperties properties;

    @BeforeEach
    void setUp() {
        properties = new SyntheticHealthCheckProperties();
        properties.setEndpoints(List.of("/actuator/health"));
        service = new SyntheticHealthCheckService(properties, alertService, restTemplate);
    }

    @Test
    void runHealthChecks_healthyEndpoint_noAlert() {
        when(restTemplate.getForEntity(anyString(), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"status\":\"UP\"}"));

        service.runHealthChecks();

        verify(alertService, never()).alert(any(), any(), anyString());
    }

    @Test
    void runHealthChecks_non2xx_alerts() {
        when(restTemplate.getForEntity(anyString(), eq(String.class)))
                .thenReturn(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("down"));

        service.runHealthChecks();

        verify(alertService).alert(any(), any(), anyString());
    }

    @Test
    void runHealthChecks_httpStatusCodeException_alerts() {
        when(restTemplate.getForEntity(anyString(), eq(String.class)))
                .thenThrow(new HttpServerErrorException(HttpStatus.BAD_GATEWAY));

        service.runHealthChecks();

        verify(alertService).alert(any(), any(), anyString());
    }

    @Test
    void runHealthChecks_resourceAccessException_alerts() {
        when(restTemplate.getForEntity(anyString(), eq(String.class)))
                .thenThrow(new ResourceAccessException("connection refused"));

        service.runHealthChecks();

        verify(alertService).alert(any(), any(), anyString());
    }

    @Test
    void runHealthChecks_genericException_alerts() {
        when(restTemplate.getForEntity(anyString(), eq(String.class)))
                .thenThrow(new IllegalStateException("boom"));

        service.runHealthChecks();

        verify(alertService).alert(any(), any(), anyString());
    }

    @Test
    void runHealthChecks_emptyEndpoints_doesNothing() {
        properties.setEndpoints(List.of());
        service.runHealthChecks();
        verify(restTemplate, never()).getForEntity(anyString(), eq(String.class));
        verify(alertService, never()).alert(any(), any(), anyString());
    }
}
package com.bhukkad.restaurant.api.controller;

import com.bhukkad.restaurant.domain.entity.Tenant;
import com.bhukkad.restaurant.domain.service.impl.TenantService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.bhukkad.restaurant.api.dto.response.ApiResponse;

@ExtendWith(MockitoExtension.class)
class TenantControllerTest {

    @Mock
    private TenantService tenantService;

    @InjectMocks
    private TenantController controller;

    @Test
    void list_wrapsTenantsInSuccessEnvelope() {
        Tenant tenant = new Tenant();
        tenant.setId(1L);
        tenant.setName("Bhukkad");
        tenant.setDomain("bhukkad.in");
        when(tenantService.listAll()).thenReturn(List.of(tenant));

        ResponseEntity<ApiResponse<List<Tenant>>> response = controller.list();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().success()).isTrue();
        assertThat(response.getBody().data()).extracting(Tenant::getDomain).containsExactly("bhukkad.in");
    }

    @Test
    void create_delegatesToService() {
        Tenant input = new Tenant();
        input.setName("Bhukkad");
        input.setDomain("bhukkad.in");
        when(tenantService.create(input)).thenReturn(input);

        ResponseEntity<ApiResponse<Tenant>> response = controller.create(input);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().message()).isEqualTo("Tenant created");
        assertThat(response.getBody().data().getDomain()).isEqualTo("bhukkad.in");
        verify(tenantService).create(input);
    }

    @Test
    void update_delegatesToService() {
        Tenant patch = new Tenant();
        patch.setName("Renamed");
        Tenant updated = new Tenant();
        updated.setId(7L);
        updated.setName("Renamed");
        updated.setDomain("bhukkad.in");
        when(tenantService.update(7L, patch)).thenReturn(updated);

        ResponseEntity<ApiResponse<Tenant>> response = controller.update(7L, patch);

        assertThat(response.getBody().message()).isEqualTo("Tenant updated");
        assertThat(response.getBody().data().getName()).isEqualTo("Renamed");
        verify(tenantService).update(7L, patch);
    }

    @Test
    void deactivate_returnsSuccessWithNullData() {
        ResponseEntity<ApiResponse<Void>> response = controller.deactivate(7L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().message()).isEqualTo("Tenant deactivated");
        assertThat(response.getBody().data()).isNull();
        verify(tenantService).deactivate(7L);
    }
}

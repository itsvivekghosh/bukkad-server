package com.bhukkad.admin.api.controller;

import com.bhukkad.admin.domain.service.AuditService;
import com.bhukkad.admin.api.controller.AdminUserOpsController.ServiceMeshClient;
import com.bhukkad.common.error.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@ExtendWith(MockitoExtension.class)
class AdminUserOpsControllerTest {

    @Mock private AuditService auditService;
    @Mock private ServiceMeshClient mesh;

    @InjectMocks private AdminUserOpsController controller;

    private static LinkedHashMap<String, Object> result() {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        map.put("ok", true);
        return map;
    }

    @Test
    void users_proxiesPagingToIdentity() {
        when(mesh.get("/api/v1/internal/admin/users?page=2&size=5")).thenReturn(result());

        assertThat(controller.users(2, 5)).containsEntry("ok", true);
    }

    @Test
    void user_proxiesLookup() {
        when(mesh.get("/api/v1/internal/admin/users/7")).thenReturn(result());

        assertThat(controller.user(7L)).containsEntry("ok", true);
    }

    @Test
    void verifyOwner_proxiesAndAudits() {
        when(mesh.put("/api/v1/internal/admin/users/7/verify?expectedRole=RESTAURANT_OWNER"))
                .thenReturn(result());

        Map<String, Object> body = controller.verifyOwner(7L);

        assertThat(body).containsEntry("message", "Restaurant owner verified");
        verify(auditService).record(eq("ACCOUNT_VERIFIED"), eq("USER"), eq("7"), isNull(), isNull());
    }

    @Test
    void verifyAgent_proxiesAndAudits() {
        when(mesh.put("/api/v1/internal/admin/users/8/verify?expectedRole=DELIVERY_AGENT"))
                .thenReturn(result());

        assertThat(controller.verifyAgent(8L)).containsEntry("message", "Delivery agent verified");
        verify(auditService).record(eq("ACCOUNT_VERIFIED"), eq("USER"), eq("8"), isNull(), isNull());
    }

    @Test
    void activate_proxiesAndAudits() {
        when(mesh.put("/api/v1/internal/admin/users/9/activate")).thenReturn(result());

        assertThat(controller.activate(9L)).containsEntry("message", "Account activated");
        verify(auditService).record("USER_ACTIVATED", "USER", "9", null, null);
    }

    @Test
    void deactivate_proxiesAndAudits() {
        when(mesh.put("/api/v1/internal/admin/users/9/deactivate")).thenReturn(result());

        assertThat(controller.deactivate(9L)).containsEntry("message", "Account deactivated");
        verify(auditService).record("USER_DEACTIVATED", "USER", "9", null, null);
    }

    @Test
    void erase_proxiesAndAudits() {
        when(mesh.post("/api/v1/internal/admin/users/9/erase", null)).thenReturn(result());

        assertThat(controller.erase(9L)).containsEntry("message", "User data erased");
        verify(auditService).record("USER_ERASED", "USER", "9", null, null);
    }

    @Test
    void cities_preservesUpstreamStatusAndBody() {
        when(mesh.getStatus("/api/v1/internal/cities")).thenReturn(200);
        when(mesh.getRaw("/api/v1/internal/cities")).thenReturn(List.of("Mumbai", "Pune"));

        ResponseEntity<?> response = controller.cities();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(List.of("Mumbai", "Pune"));
    }

    @Test
    void createCity_requiresName() {
        assertThatThrownBy(() -> controller.createCity(null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("name is required");
        assertThatThrownBy(() -> controller.createCity(Map.of("name", "  ")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("name is required");
    }

    @Test
    void createCity_trimsAndProxies() {
        ResponseEntity<Map<String, Object>> upstream =
                ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", 1));
        when(mesh.postForEntity("/api/v1/internal/cities", Map.of("name", "Goa"))).thenReturn(upstream);

        ResponseEntity<Map<String, Object>> response = controller.createCity(Map.of("name", " Goa "));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).containsEntry("id", 1);
    }

    /** Tests for the real mesh client (service-token header, verb coverage, status passthrough). */
    @Test
    void meshClient_endToEndAgainstMockServer() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        com.bhukkad.common.security.ServiceJwtAuthTokenProvider tokenProvider =
                org.mockito.Mockito.mock(com.bhukkad.common.security.ServiceJwtAuthTokenProvider.class);
        when(tokenProvider.serviceToken()).thenReturn("mesh-token");

        ServiceMeshClient client = new ServiceMeshClient(builder, tokenProvider);
        org.springframework.test.util.ReflectionTestUtils.setField(client, "identityUri", "http://identity:8080");
        org.springframework.test.util.ReflectionTestUtils.setField(client, "deliveryUri", "http://delivery:8080");

        // All expectations must precede the first actual request.
        server.expect(requestTo("http://identity:8080/api/v1/internal/admin/users?page=0&size=10"))
                .andExpect(method(HttpMethod.GET)).andExpect(header("X-Service-Token", "mesh-token"))
                .andRespond(withSuccess("{\"page\":0}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://identity:8080/api/v1/internal/admin/users/3/verify"))
                .andRespond(withSuccess("{\"verified\":true}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://identity:8080/api/v1/internal/admin/users/3/erase"))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers
                        .jsonPath("$.mode").value("crypto"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://delivery:8080/api/v1/internal/cities"))
                .andRespond(withSuccess("[\"Mumbai\"]", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://delivery:8080/api/v1/internal/cities"))
                .andRespond(withSuccess());
        server.expect(requestTo("http://delivery:8080/api/v1/internal/cities"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(requestTo("http://delivery:8080/api/v1/internal/cities"))
                .andRespond(withStatus(HttpStatus.CREATED)
                        .body("{\"id\":4}").contentType(MediaType.APPLICATION_JSON));

        assertThat(client.get("/api/v1/internal/admin/users?page=0&size=10")).containsEntry("page", 0);
        assertThat(client.put("/api/v1/internal/admin/users/3/verify")).containsEntry("verified", true);
        assertThat(client.post("/api/v1/internal/admin/users/3/erase", Map.of("mode", "crypto"))).isEmpty();
        assertThat(client.getRaw("/api/v1/internal/cities")).isEqualTo(List.of("Mumbai"));
        assertThat(client.getStatus("/api/v1/internal/cities")).isEqualTo(200);
        // Suspected src/main bug: getStatus()/cities() cannot pass through 4xx/5xx —
        // toBodilessEntity() throws before the status is ever read (documented via assert).
        assertThatThrownBy(() -> client.getStatus("/api/v1/internal/cities"))
                .isInstanceOf(org.springframework.web.client.HttpClientErrorException.class);
        ResponseEntity<Map<String, Object>> created =
                client.postForEntity("/api/v1/internal/cities", null);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).containsEntry("id", 4);

        server.verify();
    }

    @Test
    void meshClient_omitsAbsOrBlankServiceTokenHeader() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        com.bhukkad.common.security.ServiceJwtAuthTokenProvider tokenProvider =
                org.mockito.Mockito.mock(com.bhukkad.common.security.ServiceJwtAuthTokenProvider.class);
        when(tokenProvider.serviceToken()).thenReturn("  ");

        ServiceMeshClient client = new ServiceMeshClient(builder, tokenProvider);
        org.springframework.test.util.ReflectionTestUtils.setField(client, "identityUri", "http://identity:8080");

        server.expect(requestTo("http://identity:8080/api/v1/internal/admin/users/1"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        assertThat(client.get("/api/v1/internal/admin/users/1")).isEmpty();
        server.verify();
    }
}

package com.bhukkad.controller;

import com.bhukkad.compliance.ConsentRecord;
import com.bhukkad.compliance.ConsentService;
import com.bhukkad.compliance.DataExportRequest;
import com.bhukkad.compliance.DataExportService;
import com.bhukkad.compliance.DataDeletionService;
import com.bhukkad.dto.response.ApiResponse;
import com.bhukkad.security.SecurityUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class ComplianceControllerTest {

    @Mock
    private ConsentService consentService;

    @Mock
    private DataExportService dataExportService;

    @Mock
    private DataDeletionService dataDeletionService;

    @Mock
    private SecurityUtils securityUtils;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ComplianceController controller = new ComplianceController(
                consentService,
                dataExportService,
                dataDeletionService,
                securityUtils);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void getConsents_returnsConsents() throws Exception {
        when(securityUtils.getCurrentUserId()).thenReturn(1L);
        when(consentService.getConsents(1L)).thenReturn(java.util.Collections.singletonList(
                new ConsentRecord(1L, 1L, "MARKETING", true, "web", java.time.LocalDateTime.now())));

        mockMvc.perform(get("/api/v1/compliance/consents")
                        .header("Authorization", "Bearer token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].purpose").value("MARKETING"));
    }

    @Test
    void setConsent_createsNewConsent() throws Exception {
        when(securityUtils.getCurrentUserId()).thenReturn(1L);
        when(consentService.setConsent(1L, "MARKETING", true, "customer-portal"))
                .thenReturn(new ConsentRecord(1L, 1L, "MARKETING", true, "web", java.time.LocalDateTime.now()));

        mockMvc.perform(put("/api/v1/compliance/consents/MARKETING")
                        .header("Authorization", "Bearer token")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"granted\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.purpose").value("MARKETING"));
    }

    @Test
    void requestExport_createsAndProcessesExport() throws Exception {
        when(securityUtils.getCurrentUserId()).thenReturn(1L);
        DataExportRequest request = new DataExportRequest();
        request.setId(1L);
        request.setStatus(DataExportRequest.Status.REQUESTED);
        when(dataExportService.createRequest(1L)).thenReturn(request);
        when(dataExportService.processRequest(1L))
                .thenReturn(new DataExportRequest(1L, 5L, java.time.LocalDateTime.now(),
                        DataExportRequest.Status.READY, "{}", java.time.LocalDateTime.now()));

        mockMvc.perform(post("/api/v1/compliance/export")
                        .header("Authorization", "Bearer token"))
                .andExpect(status().isAccepted());
    }

    @Test
    void getExport_returnsPayload() throws Exception {
        when(securityUtils.getCurrentUserId()).thenReturn(1L);
        when(dataExportService.getExport(1L)).thenReturn("{\"data\":\"export\"}");

        mockMvc.perform(get("/api/v1/compliance/export")
                        .header("Authorization", "Bearer token"))
                .andExpect(status().isOk())
                .andExpect(content().string("{\"data\":\"export\"}"));
    }

    @Test
    void eraseUser_adminOnly() throws Exception {
        when(dataDeletionService.deleteUser(1L)).thenReturn(5);

        mockMvc.perform(post("/api/v1/compliance/users/1/erase")
                        .header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("User anonymized; 5 address record(s) removed"));
    }
}

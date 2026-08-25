package com.bhukkad.controller;

import com.bhukkad.dto.request.GroupOrderCreateRequest;
import com.bhukkad.dto.request.GroupOrderInviteRequest;
import com.bhukkad.dto.request.GroupOrderSplitRequest;
import com.bhukkad.dto.response.ApiResponse;
import com.bhukkad.dto.response.GroupOrderResponse;
import com.bhukkad.order.GroupOrderService;
import com.bhukkad.security.SecurityUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class GroupOrderControllerTest {

    @Mock
    private com.bhukkad.order.GroupOrderService groupOrderService;

    @Mock
    private com.bhukkad.security.SecurityUtils securityUtils;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        GroupOrderController controller = new GroupOrderController(
                groupOrderService, securityUtils);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void createGroupOrder_createsSuccessfully() throws Exception {
        when(securityUtils.getCurrentUserId()).thenReturn(1L);
        when(groupOrderService.createGroupOrder(1L, "Family Dinner"))
                .thenReturn(GroupOrderResponse.builder()
                        .id(1L)
                        .hostUserId(1L)
                        .title("Family Dinner")
                        .status("OPEN")
                        .createdAt(java.time.LocalDateTime.now())
                        .build());

        mockMvc.perform(post("/api/v1/customers/group-orders")
                        .header("Authorization", "Bearer token")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Family Dinner\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.title").value("Family Dinner"));
    }

    @Test
    void inviteMember_invitesSuccessfully() throws Exception {
        when(securityUtils.getCurrentUserId()).thenReturn(1L);
        when(groupOrderService.inviteMember(1L, 1L, "9876543210"))
                .thenReturn(com.bhukkad.dto.response.GroupOrderResponse.builder()
                        .id(1L)
                        .hostUserId(1L)
                        .title("Family Dinner")
                        .status("OPEN")
                        .build());

        mockMvc.perform(post("/api/v1/customers/group-orders/1/invite")
                        .header("Authorization", "Bearer token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"9876543210\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("Family Dinner"));
    }

    @Test
    void joinGroup_joinsSuccessfully() throws Exception {
        when(securityUtils.getCurrentUserId()).thenReturn(2L);
        when(groupOrderService.joinGroup(1L, 2L))
                .thenReturn(com.bhukkad.dto.response.GroupOrderResponse.builder()
                        .id(1L)
                        .hostUserId(1L)
                        .title("Family Dinner")
                        .status("OPEN")
                        .build());

        mockMvc.perform(post("/api/v1/customers/group-orders/1/join")
                        .header("Authorization", "Bearer token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("Family Dinner"));
    }

    @Test
    void getGroup_returnsGroup() throws Exception {
        when(securityUtils.getCurrentUserId()).thenReturn(1L);
        when(groupOrderService.getGroup(1L, 1L))
                .thenReturn(com.bhukkad.dto.response.GroupOrderResponse.builder()
                        .id(1L)
                        .hostUserId(1L)
                        .title("Family Dinner")
                        .status("OPEN")
                        .build());

        mockMvc.perform(get("/api/v1/customers/group-orders/1")
                        .header("Authorization", "Bearer token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("Family Dinner"));
    }

    @Test
    void splitPayment_recordsSplit() throws Exception {
        when(securityUtils.getCurrentUserId()).thenReturn(1L);
        when(groupOrderService.splitPayment(eq(1L), eq(1L), any()))
                .thenReturn(com.bhukkad.dto.response.GroupOrderResponse.builder()
                        .id(1L)
                        .hostUserId(1L)
                        .title("Family Dinner")
                        .status("CLOSED")
                        .build());

        Map<Long, Double> shares = Map.of(1L, 50.0, 2L, 50.0);
        mockMvc.perform(post("/api/v1/customers/group-orders/1/split")
                        .header("Authorization", "Bearer token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shares\":{\"1\":50.0,\"2\":50.0}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CLOSED"));
    }

    @Test
    void placeGroupOrder_placesSuccessfully() throws Exception {
        when(securityUtils.getCurrentUserId()).thenReturn(1L);
        when(groupOrderService.placeGroupOrder(1L, 1L))
                .thenReturn(com.bhukkad.dto.response.GroupOrderResponse.builder()
                        .id(1L)
                        .hostUserId(1L)
                        .title("Family Dinner")
                        .status("PLACED")
                        .build());

        mockMvc.perform(post("/api/v1/customers/group-orders/1/place")
                        .header("Authorization", "Bearer token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PLACED"));
    }
}

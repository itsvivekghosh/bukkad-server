package com.bhukkad.controller;

import com.bhukkad.dto.request.SubscribeMembershipRequest;
import com.bhukkad.dto.request.SupportTicketRequest;
import com.bhukkad.dto.response.ApiResponse;
import com.bhukkad.dto.response.CursorPagedResponse;
import com.bhukkad.dto.response.MembershipPlanResponse;
import com.bhukkad.dto.response.MembershipStatusResponse;
import com.bhukkad.dto.response.PagedResponse;
import com.bhukkad.dto.response.SupportTicketResponse;
import com.bhukkad.dto.response.WalletTransactionResponse;
import com.bhukkad.membership.MembershipService;
import com.bhukkad.security.SecurityUtils;
import com.bhukkad.support.SupportTicketService;
import com.bhukkad.wallet.WalletQueryService;
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
class CustomerGrowthControllerTest {

    @Mock
    private WalletQueryService walletQueryService;
    @Mock
    private SupportTicketService supportTicketService;
    @Mock
    private MembershipService membershipService;
    @Mock
    private SecurityUtils securityUtils;

    @InjectMocks
    private CustomerGrowthController controller;

    @Test
    void getWalletTransactions_delegatesWithCurrentUser() {
        PagedResponse<WalletTransactionResponse> page = PagedResponse.<WalletTransactionResponse>builder().build();
        when(securityUtils.getCurrentUserId()).thenReturn(5L);
        when(walletQueryService.getTransactions(5L, 1, 10)).thenReturn(page);

        ResponseEntity<ApiResponse<PagedResponse<WalletTransactionResponse>>> response =
                controller.getWalletTransactions(1, 10);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertSame(page, response.getBody().getData());
        verify(walletQueryService).getTransactions(5L, 1, 10);
    }

    @Test
    void getWalletTransactionsByCursor_delegatesWithCurrentUser() {
        CursorPagedResponse<WalletTransactionResponse> page =
                CursorPagedResponse.<WalletTransactionResponse>builder().build();
        when(securityUtils.getCurrentUserId()).thenReturn(5L);
        when(walletQueryService.getTransactionsByCursor(5L, "abc", 25)).thenReturn(page);

        ResponseEntity<ApiResponse<CursorPagedResponse<WalletTransactionResponse>>> response =
                controller.getWalletTransactionsByCursor("abc", 25);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertSame(page, response.getBody().getData());
    }

    @Test
    void createSupportTicket_returnsCreatedTicket() {
        SupportTicketRequest request = new SupportTicketRequest();
        SupportTicketResponse ticket = SupportTicketResponse.builder().build();
        when(supportTicketService.create(request)).thenReturn(ticket);

        ResponseEntity<ApiResponse<SupportTicketResponse>> response = controller.createSupportTicket(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Support ticket created", response.getBody().getMessage());
        assertSame(ticket, response.getBody().getData());
    }

    @Test
    void listSupportTickets_returnsTickets() {
        List<SupportTicketResponse> tickets = List.of(SupportTicketResponse.builder().build());
        when(supportTicketService.listCustomerTickets()).thenReturn(tickets);

        ResponseEntity<ApiResponse<List<SupportTicketResponse>>> response = controller.listSupportTickets();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertSame(tickets, response.getBody().getData());
    }

    @Test
    void listMembershipPlans_returnsPlans() {
        List<MembershipPlanResponse> plans = List.of(MembershipPlanResponse.builder().build());
        when(membershipService.listPlans()).thenReturn(plans);

        ResponseEntity<ApiResponse<List<MembershipPlanResponse>>> response = controller.listMembershipPlans();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertSame(plans, response.getBody().getData());
    }

    @Test
    void getMembershipStatus_returnsStatusForCurrentUser() {
        MembershipStatusResponse status = MembershipStatusResponse.builder().build();
        when(securityUtils.getCurrentUserId()).thenReturn(5L);
        when(membershipService.getActiveMembership(5L)).thenReturn(status);

        ResponseEntity<ApiResponse<MembershipStatusResponse>> response = controller.getMembershipStatus();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertSame(status, response.getBody().getData());
    }

    @Test
    void subscribeMembership_returnsActivatedStatus() {
        SubscribeMembershipRequest request = new SubscribeMembershipRequest();
        MembershipStatusResponse status = MembershipStatusResponse.builder().build();
        when(membershipService.subscribe(request)).thenReturn(status);

        ResponseEntity<ApiResponse<MembershipStatusResponse>> response = controller.subscribeMembership(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Membership activated", response.getBody().getMessage());
        assertSame(status, response.getBody().getData());
    }
}

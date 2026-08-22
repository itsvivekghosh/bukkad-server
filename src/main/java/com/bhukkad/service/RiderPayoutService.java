package com.bhukkad.service;

import com.bhukkad.dto.response.CursorPagedResponse;
import com.bhukkad.dto.response.RiderEarningsSummaryResponse;
import com.bhukkad.dto.response.RiderPayoutResponse;
import com.bhukkad.dto.response.PagedResponse;

public interface RiderPayoutService {
    RiderEarningsSummaryResponse getEarningsSummary();
    PagedResponse<RiderPayoutResponse> getPayoutHistory(int page, int size);
    CursorPagedResponse<RiderPayoutResponse> getPayoutHistoryByCursor(String cursor, int size);
    int settlePendingPayouts(Long agentId);
}

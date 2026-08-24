package com.bhukkad.churn;

import com.bhukkad.dto.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Admin retention dashboard (FEATURE #12): high-risk cohort review plus on-demand
 * re-scoring of a single customer.
 */
@RestController
@RequestMapping("/api/v1/admin/churn")
@RequiredArgsConstructor
public class ChurnAdminController {

    private final ChurnPredictionService churnPredictionService;

    @GetMapping("/high-risk")
    public ResponseEntity<ApiResponse<List<ChurnScore>>> highRisk() {
        return ResponseEntity.ok(ApiResponse.success(churnPredictionService.highRiskCustomers()));
    }

    @PostMapping("/rescore/{userId}")
    public ResponseEntity<ApiResponse<ChurnScore>> rescore(@PathVariable("userId") Long userId) {
        return ResponseEntity.ok(ApiResponse.success(churnPredictionService.scoreCustomer(userId)));
    }
}

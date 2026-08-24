package com.bhukkad.experiment;

import com.bhukkad.dto.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Admin view over experiment assignments — the cohort census used to compute
 * metric lift (conversion, AOV, retention) in the analytics pipeline.
 */
@RestController
@RequestMapping("/api/v1/admin/experiments")
@RequiredArgsConstructor
public class ExperimentAdminController {

    private final ExperimentAssignmentService assignmentService;

    @GetMapping("/{experimentKey}/exposures")
    public ResponseEntity<ApiResponse<Map<String, Long>>> exposures(
            @PathVariable("experimentKey") String experimentKey) {
        return ResponseEntity.ok(ApiResponse.success(assignmentService.exposureCounts(experimentKey)));
    }
}

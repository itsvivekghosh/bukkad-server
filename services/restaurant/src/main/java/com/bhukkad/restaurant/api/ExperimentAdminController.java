package com.bhukkad.restaurant.api;

import com.bhukkad.restaurant.experiment.ExperimentAssignmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/experiments")
@RequiredArgsConstructor
public class ExperimentAdminController {

    private final ExperimentAssignmentService assignmentService;

    @GetMapping("/{experimentKey}/exposures")
    public ResponseEntity<Map<String, Long>> exposures(
            @PathVariable("experimentKey") String experimentKey) {
        return ResponseEntity.ok(assignmentService.exposureCounts(experimentKey));
    }
}

package com.bhukkad.common.aws;

import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.guardduty.GuardDutyClient;
import software.amazon.awssdk.services.guardduty.model.*;

/**
 * GuardDuty service for threat detection.
 * <p>
 * Provides operations for managing GuardDuty detectors and findings
 * for security threat monitoring. Fully testable against Floci emulator locally.
 */
@Service
public class GuardDutyService {

    private final GuardDutyClient guardDutyClient;
    private final AwsProperties awsProperties;

    public GuardDutyService(GuardDutyClient guardDutyClient, AwsProperties awsProperties) {
        this.guardDutyClient = guardDutyClient;
        this.awsProperties = awsProperties;
    }

    /**
     * Creates a GuardDuty detector for threat monitoring.
     */
    public CreateDetectorResponse createDetector() {
        return guardDutyClient.createDetector(CreateDetectorRequest.builder()
                .enable(true)
                .build());
    }

    /**
     * Lists all GuardDuty detectors.
     */
    public ListDetectorsResponse listDetectors() {
        return guardDutyClient.listDetectors(ListDetectorsRequest.builder().build());
    }

    /**
     * Gets detector details.
     */
    public GetDetectorResponse getDetector(String detectorId) {
        return guardDutyClient.getDetector(GetDetectorRequest.builder()
                .detectorId(detectorId)
                .build());
    }

    /**
     * Lists security findings from a detector.
     */
    public ListFindingsResponse listFindings(String detectorId) {
        return guardDutyClient.listFindings(ListFindingsRequest.builder()
                .detectorId(detectorId)
                .build());
    }

    /**
     * Archives findings.
     */
    public ArchiveFindingsResponse archiveFindings(String detectorId, java.util.List<String> findingIds) {
        return guardDutyClient.archiveFindings(ArchiveFindingsRequest.builder()
                .detectorId(detectorId)
                .findingIds(findingIds)
                .build());
    }
}
package com.bhukkad.common.aws;

import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.securityhub.SecurityHubClient;
import software.amazon.awssdk.services.securityhub.model.*;

/**
 * SecurityHub service for security posture management.
 * <p>
 * Provides operations for managing SecurityHub standards, controls,
 * and security findings aggregation. Fully testable against Floci emulator locally.
 */
@Service
public class SecurityHubService {

    private final SecurityHubClient securityHubClient;
    private final AwsProperties awsProperties;

    public SecurityHubService(SecurityHubClient securityHubClient, AwsProperties awsProperties) {
        this.securityHubClient = securityHubClient;
        this.awsProperties = awsProperties;
    }

    /**
     * Enables SecurityHub in the account.
     */
    public EnableSecurityHubResponse enableSecurityHub() {
        return securityHubClient.enableSecurityHub(EnableSecurityHubRequest.builder().build());
    }

    /**
     * Disables SecurityHub in the account.
     */
    public DisableSecurityHubResponse disableSecurityHub() {
        return securityHubClient.disableSecurityHub(DisableSecurityHubRequest.builder().build());
    }

    /**
     * Describes SecurityHub status.
     */
    public DescribeHubResponse describeHub() {
        return securityHubClient.describeHub(DescribeHubRequest.builder().build());
    }

    /**
     * Lists enabled security standards.
     */
    public DescribeStandardsResponse describeStandards() {
        return securityHubClient.describeStandards(DescribeStandardsRequest.builder().build());
    }

    /**
     * Gets security findings.
     */
    public GetFindingsResponse getFindings() {
        return securityHubClient.getFindings(GetFindingsRequest.builder().build());
    }

    /**
     * Updates finding severity.
     */
    public BatchUpdateFindingsResponse batchUpdateFindings(
            java.util.List<String> findingIds, String severity) {
        return securityHubClient.batchUpdateFindings(BatchUpdateFindingsRequest.builder()
                .findingIdentifiers(findingIds.stream()
                        .map(fid -> AwsSecurityFindingIdentifier.builder()
                                .id(fid)
                                .build())
                        .toList())
                .note(NoteUpdate.builder()
                        .text("Updated severity")
                        .updatedBy("bhukkad-security")
                        .build())
                .severity(SeverityUpdate.builder().label(SeverityLabel.fromValue(severity)).build())
                .build());
    }
}
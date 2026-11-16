package com.bhukkad.common.aws;

import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.route53.Route53Client;
import software.amazon.awssdk.services.route53.model.*;

/**
 * Route53 service for DNS management.
 * <p>
 * Provides operations for managing DNS hosted zones and records.
 * Fully testable against Floci emulator locally.
 */
@Service
public class Route53Service {

    private final Route53Client route53Client;
    private final AwsProperties awsProperties;

    public Route53Service(Route53Client route53Client, AwsProperties awsProperties) {
        this.route53Client = route53Client;
        this.awsProperties = awsProperties;
    }

    /**
     * Creates a public hosted zone for a domain.
     */
    public CreateHostedZoneResponse createHostedZone(String domainName, String comment) {
        return route53Client.createHostedZone(CreateHostedZoneRequest.builder()
                .name(domainName)
                .callerReference(java.util.UUID.randomUUID().toString())
                .hostedZoneConfig(HostedZoneConfig.builder()
                        .comment(comment)
                        .privateZone(false)
                        .build())
                .build());
    }

    /**
     * Lists all hosted zones.
     */
    public ListHostedZonesResponse listHostedZones() {
        return route53Client.listHostedZones(ListHostedZonesRequest.builder().build());
    }

    /**
     * Gets a hosted zone by name.
     */
    public GetHostedZoneResponse getHostedZone(String hostedZoneId) {
        return route53Client.getHostedZone(GetHostedZoneRequest.builder()
                .id(hostedZoneId)
                .build());
    }

    /**
     * Deletes a hosted zone.
     */
    public DeleteHostedZoneResponse deleteHostedZone(String hostedZoneId) {
        return route53Client.deleteHostedZone(DeleteHostedZoneRequest.builder()
                .id(hostedZoneId)
                .build());
    }

    /**
     * Creates a DNS record set in a hosted zone.
     */
    public ChangeResourceRecordSetsResponse createRecordSet(
            String hostedZoneId, String recordName, String recordType, String recordValue) {
        return route53Client.changeResourceRecordSets(ChangeResourceRecordSetsRequest.builder()
                .hostedZoneId(hostedZoneId)
                .changeBatch(ChangeBatch.builder()
                        .changes(Change.builder()
                                .action(ChangeAction.CREATE)
                                .resourceRecordSet(ResourceRecordSet.builder()
                                        .name(recordName)
                                        .type(RRType.fromValue(recordType))
                                        .ttl(300L)
                                        .resourceRecords(ResourceRecord.builder()
                                                .value(recordValue)
                                                .build())
                                        .build())
                                .build())
                        .build())
                .build());
    }
}
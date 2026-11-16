package com.bhukkad.common.aws;

import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.CreateDbInstanceRequest;
import software.amazon.awssdk.services.rds.model.CreateDbInstanceResponse;
import software.amazon.awssdk.services.rds.model.DeleteDbInstanceRequest;
import software.amazon.awssdk.services.rds.model.DeleteDbInstanceResponse;
import software.amazon.awssdk.services.rds.model.DescribeDbInstancesRequest;
import software.amazon.awssdk.services.rds.model.DescribeDbInstancesResponse;
import software.amazon.awssdk.services.rds.model.ModifyDbInstanceRequest;
import software.amazon.awssdk.services.rds.model.ModifyDbInstanceResponse;

/**
 * RDS service for managed database management.
 * <p>
 * Provides operations for creating and managing RDS database instances.
 * Fully testable against Floci emulator locally.
 */
@Service
public class RdsService {

    private final RdsClient rdsClient;
    private final AwsProperties awsProperties;

    public RdsService(RdsClient rdsClient, AwsProperties awsProperties) {
        this.rdsClient = rdsClient;
        this.awsProperties = awsProperties;
    }

    /**
     * Creates an RDS DB instance.
     */
    public CreateDbInstanceResponse createDbInstance(
            String dbInstanceIdentifier, String dbInstanceClass, String engine,
            String masterUsername, String masterUserPassword) {
        return rdsClient.createDBInstance(CreateDbInstanceRequest.builder()
                .dbInstanceIdentifier(dbInstanceIdentifier)
                .dbInstanceClass(dbInstanceClass)
                .engine(engine)
                .masterUsername(masterUsername)
                .masterUserPassword(masterUserPassword)
                .allocatedStorage(20)
                .build());
    }

    /**
     * Deletes an RDS DB instance.
     */
    public DeleteDbInstanceResponse deleteDbInstance(String dbInstanceIdentifier) {
        return rdsClient.deleteDBInstance(DeleteDbInstanceRequest.builder()
                .dbInstanceIdentifier(dbInstanceIdentifier)
                .skipFinalSnapshot(true)
                .build());
    }

    /**
     * Lists all RDS DB instances.
     */
    public DescribeDbInstancesResponse describeDbInstances() {
        return rdsClient.describeDBInstances(DescribeDbInstancesRequest.builder().build());
    }

    /**
     * Describes a specific DB instance.
     */
    public DescribeDbInstancesResponse describeDbInstance(String dbInstanceIdentifier) {
        return rdsClient.describeDBInstances(DescribeDbInstancesRequest.builder()
                .dbInstanceIdentifier(dbInstanceIdentifier)
                .build());
    }
}
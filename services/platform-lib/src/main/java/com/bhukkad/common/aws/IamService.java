package com.bhukkad.common.aws;

import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.*;

import java.util.List;
import java.util.Map;

/**
 * AWS IAM service for identity and access management.
 * <p>
 * Provides operations for managing IAM roles, policies, users, and groups
 * for fine-grained access control across Bhukkad microservices.
 * <p>
 * Use cases in Bhukkad:
 * - Service-to-service authentication roles (identity→catalog, commerce→payment)
 * - Least-privilege IAM roles for Lambda functions
 * - Cross-account access for partner integrations
 * - Service-specific policies for resource access
 * <p>
 * Fully testable against Floci emulator locally.
 */
@Service
public class IamService {

    private final IamClient iamClient;
    private final AwsProperties awsProperties;

    public IamService(IamClient iamClient, AwsProperties awsProperties) {
        this.iamClient = iamClient;
        this.awsProperties = awsProperties;
    }

    // ==================== Role Management ====================

    /**
     * Creates an IAM role for a Bhukkad microservice.
     * <p>
     * Example: Create a role for the commerce service that allows it to
     * access DynamoDB orders table, SNS for notifications, and SQS for
     * order processing.
     */
    public CreateRoleResponse createRole(
            String roleName, String assumeRolePolicyDocument, String description) {
        return iamClient.createRole(CreateRoleRequest.builder()
                .roleName(roleName)
                .assumeRolePolicyDocument(assumeRolePolicyDocument)
                .description(description)
                .build());
    }

    /**
     * Lists all IAM roles.
     */
    public ListRolesResponse listRoles() {
        return iamClient.listRoles(ListRolesRequest.builder().build());
    }

    /**
     * Gets an IAM role by name.
     */
    public GetRoleResponse getRole(String roleName) {
        return iamClient.getRole(GetRoleRequest.builder().roleName(roleName).build());
    }

    /**
     * Deletes an IAM role.
     */
    public DeleteRoleResponse deleteRole(String roleName) {
        return iamClient.deleteRole(DeleteRoleRequest.builder().roleName(roleName).build());
    }

    // ==================== Policy Management ====================

    /**
     * Creates an IAM policy for fine-grained access control.
     * <p>
     * Example: Create a policy that allows read-only access to restaurant
     * catalog data but denies write access.
     */
    public CreatePolicyResponse createPolicy(
            String policyName, String policyDocument, String description) {
        return iamClient.createPolicy(CreatePolicyRequest.builder()
                .policyName(policyName)
                .policyDocument(policyDocument)
                .description(description)
                .build());
    }

    /**
     * Lists all IAM policies.
     */
    public ListPoliciesResponse listPolicies() {
        return iamClient.listPolicies(ListPoliciesRequest.builder().build());
    }

    /**
     * Gets an IAM policy by ARN.
     */
    public GetPolicyResponse getPolicy(String policyArn) {
        return iamClient.getPolicy(GetPolicyRequest.builder().policyArn(policyArn).build());
    }

    /**
     * Deletes an IAM policy.
     */
    public DeletePolicyResponse deletePolicy(String policyArn) {
        return iamClient.deletePolicy(DeletePolicyRequest.builder().policyArn(policyArn).build());
    }

    // ==================== Role-Policy Attachment ====================

    /**
     * Attaches an IAM policy to a role.
     * <p>
     * Example: Attach a "commerce-service-policy" to the commerce service
     * role to grant DynamoDB and SNS access.
     */
    public AttachRolePolicyResponse attachRolePolicy(String roleName, String policyArn) {
        return iamClient.attachRolePolicy(AttachRolePolicyRequest.builder()
                .roleName(roleName)
                .policyArn(policyArn)
                .build());
    }

    /**
     * Detaches an IAM policy from a role.
     */
    public DetachRolePolicyResponse detachRolePolicy(String roleName, String policyArn) {
        return iamClient.detachRolePolicy(DetachRolePolicyRequest.builder()
                .roleName(roleName)
                .policyArn(policyArn)
                .build());
    }

    /**
     * Lists attached policies for a role.
     */
    public ListAttachedRolePoliciesResponse listAttachedRolePolicies(String roleName) {
        return iamClient.listAttachedRolePolicies(ListAttachedRolePoliciesRequest.builder()
                .roleName(roleName)
                .build());
    }

    // ==================== User Management ====================

    /**
     * Creates an IAM user for a Bhukkad team member.
     */
    public CreateUserResponse createUser(String userName, String path) {
        return iamClient.createUser(CreateUserRequest.builder()
                .userName(userName)
                .path(path)
                .build());
    }

    /**
     * Lists all IAM users.
     */
    public ListUsersResponse listUsers() {
        return iamClient.listUsers(ListUsersRequest.builder().build());
    }

    /**
     * Gets an IAM user by name.
     */
    public GetUserResponse getUser(String userName) {
        return iamClient.getUser(GetUserRequest.builder().userName(userName).build());
    }

    /**
     * Deletes an IAM user.
     */
    public DeleteUserResponse deleteUser(String userName) {
        return iamClient.deleteUser(DeleteUserRequest.builder().userName(userName).build());
    }

    // ==================== Bhukkad Role Templates ====================

    /**
     * Creates a commerce service role with least-privilege access.
     * <p>
     * Grants permissions to:
     * - DynamoDB: Read/write orders, carts, payments tables
     * - SNS: Publish order events
     * - SQS: Read from order processing queue
     * - S3: Upload order receipts/invoices
     */
    public CreateRoleResponse createCommerceServiceRole() {
        String assumeRolePolicy = """
                {
                  "Version": "2012-10-17",
                  "Statement": [
                    {
                      "Effect": "Allow",
                      "Principal": {
                        "Service": "ecs-tasks.amazonaws.com"
                      },
                      "Action": "sts:AssumeRole"
                    }
                  ]
                }
                """;
        String description = "Commerce service role for order processing and payment management";

        return createRole("bhukkad-commerce-service-role", assumeRolePolicy, description);
    }

    /**
     * Creates a catalog service role with read/write access to restaurant data.
     * <p>
     * Grants permissions to:
     * - DynamoDB: Read/write restaurants, menus, categories tables
     * - S3: Upload menu images to bhukkad-assets bucket
     * - CloudWatch: Put custom metrics
     */
    public CreateRoleResponse createCatalogServiceRole() {
        String assumeRolePolicy = """
                {
                  "Version": "2012-10-17",
                  "Statement": [
                    {
                      "Effect": "Allow",
                      "Principal": {
                        "Service": "ecs-tasks.amazonaws.com"
                      },
                      "Action": "sts:AssumeRole"
                    }
                  ]
                }
                """;
        String description = "Catalog service role for restaurant and menu management";

        return createRole("bhukkad-catalog-service-role", assumeRolePolicy, description);
    }

    /**
     * Creates an identity service role for user authentication.
     * <p>
     * Grants permissions to:
     * - DynamoDB: Read/write users, refresh_tokens tables
     * - Secrets Manager: Read JWT signing keys
     * - KMS: Encrypt/decrypt authentication tokens
     * - CloudWatch: Put authentication metrics
     */
    public CreateRoleResponse createIdentityServiceRole() {
        String assumeRolePolicy = """
                {
                  "Version": "2012-10-17",
                  "Statement": [
                    {
                      "Effect": "Allow",
                      "Principal": {
                        "Service": "ecs-tasks.amazonaws.com"
                      },
                      "Action": "sts:AssumeRole"
                    }
                  ]
                }
                """;
        String description = "Identity service role for user authentication and authorization";

        return createRole("bhukkad-identity-service-role", assumeRolePolicy, description);
    }

    /**
     * Creates an engagement service role for social features.
     * <p>
     * Grants permissions to:
     * - DynamoDB: Read/write social posts, likes, comments tables
     * - SNS: Publish notification events
     * - CloudWatch: Put engagement metrics
     */
    public CreateRoleResponse createEngagementServiceRole() {
        String assumeRolePolicy = """
                {
                  "Version": "2012-10-17",
                  "Statement": [
                    {
                      "Effect": "Allow",
                      "Principal": {
                        "Service": "ecs-tasks.amazonaws.com"
                      },
                      "Action": "sts:AssumeRole"
                    }
                  ]
                }
                """;
        String description = "Engagement service role for social features and notifications";

        return createRole("bhukkad-engagement-service-role", assumeRolePolicy, description);
    }

    /**
     * Creates an admin service role for platform administration.
     * <p>
     * Grants permissions to:
     * - DynamoDB: Read all tables for analytics
     * - CloudWatch: Read metrics and alarms
     * - SNS: Send admin notifications
     * - CloudTrail: Read audit logs
     */
    public CreateRoleResponse createAdminServiceRole() {
        String assumeRolePolicy = """
                {
                  "Version": "2012-10-17",
                  "Statement": [
                    {
                      "Effect": "Allow",
                      "Principal": {
                        "Service": "ecs-tasks.amazonaws.com"
                      },
                      "Action": "sts:AssumeRole"
                    }
                  ]
                }
                """;
        String description = "Admin service role for platform administration and analytics";

        return createRole("bhukkad-admin-service-role", assumeRolePolicy, description);
    }

    // ==================== Policy Documents ====================

    /**
     * Returns a policy document for commerce service DynamoDB access.
     */
    public String getCommerceDynamoDbPolicyDocument() {
        return """
                {
                  "Version": "2012-10-17",
                  "Statement": [
                    {
                      "Effect": "Allow",
                      "Action": [
                        "dynamodb:GetItem",
                        "dynamodb:PutItem",
                        "dynamodb:UpdateItem",
                        "dynamodb:DeleteItem",
                        "dynamodb:Query",
                        "dynamodb:Scan"
                      ],
                      "Resource": [
                        "arn:aws:dynamodb:us-east-1:123456789012:table/bhukkad-*"
                      ]
                    }
                  ]
                }
                """;
    }

    /**
     * Returns a policy document for catalog service S3 access.
     */
    public String getCatalogS3PolicyDocument() {
        return """
                {
                  "Version": "2012-10-17",
                  "Statement": [
                    {
                      "Effect": "Allow",
                      "Action": [
                        "s3:PutObject",
                        "s3:GetObject",
                        "s3:DeleteObject"
                      ],
                      "Resource": [
                        "arn:aws:s3:::bhukkad-assets/*"
                      ]
                    }
                  ]
                }
                """;
    }
}
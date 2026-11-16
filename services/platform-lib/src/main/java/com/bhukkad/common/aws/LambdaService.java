package com.bhukkad.common.aws;

import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.*;

/**
 * AWS Lambda service for serverless compute.
 * <p>
 * Provides operations for managing Lambda functions, invoking functions,
 * and managing function versions and aliases.
 * <p>
 * Use cases in Bhukkad:
 * - Image processing for restaurant/menu photos
 * - Notification workers (email, SMS, push notifications)
 * - Order processing workers (payment validation, fraud detection)
 * - Scheduled tasks (daily analytics, cleanup jobs)
 * <p>
 * Fully testable against Floci emulator locally.
 */
@Service
public class LambdaService {

    private final LambdaClient lambdaClient;
    private final AwsProperties awsProperties;

    public LambdaService(LambdaClient lambdaClient, AwsProperties awsProperties) {
        this.lambdaClient = lambdaClient;
        this.awsProperties = awsProperties;
    }

    /**
     * Creates a Lambda function.
     */
    public CreateFunctionResponse createFunction(
            String functionName, String runtime, String handler,
            String roleArn, String codeS3Bucket, String codeS3Key,
            int memorySize, int timeoutSeconds) {
        return lambdaClient.createFunction(CreateFunctionRequest.builder()
                .functionName(functionName)
                .runtime(software.amazon.awssdk.services.lambda.model.Runtime.fromValue(runtime))
                .handler(handler)
                .role(roleArn)
                .code(software.amazon.awssdk.services.lambda.model.FunctionCode.builder()
                        .s3Bucket(codeS3Bucket)
                        .s3Key(codeS3Key)
                        .build())
                .memorySize(memorySize)
                .timeout(timeoutSeconds)
                .description("Bhukkad " + functionName + " function")
                .build());
    }

    /**
     * Invokes a Lambda function synchronously.
     */
    public InvokeResponse invokeFunction(String functionName, String payload) {
        return lambdaClient.invoke(InvokeRequest.builder()
                .functionName(functionName)
                .payload(software.amazon.awssdk.core.SdkBytes.fromUtf8String(payload))
                .invocationType(software.amazon.awssdk.services.lambda.model.InvocationType.REQUEST_RESPONSE)
                .build());
    }

    /**
     * Lists all Lambda functions.
     */
    public ListFunctionsResponse listFunctions() {
        return lambdaClient.listFunctions(ListFunctionsRequest.builder().build());
    }

    /**
     * Gets function configuration details.
     */
    public GetFunctionResponse getFunction(String functionName) {
        return lambdaClient.getFunction(GetFunctionRequest.builder()
                .functionName(functionName)
                .build());
    }

    /**
     * Deletes a Lambda function.
     */
    public DeleteFunctionResponse deleteFunction(String functionName) {
        return lambdaClient.deleteFunction(DeleteFunctionRequest.builder()
                .functionName(functionName)
                .build());
    }
}
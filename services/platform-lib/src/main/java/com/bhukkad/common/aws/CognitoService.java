package com.bhukkad.common.aws;

import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.*;

import java.util.List;

/**
 * Cognito Identity Provider service for authentication and user management.
 * <p>
 * Provides operations for user pools, user registration, authentication,
 * and token management. Fully testable against Floci emulator locally.
 */
@Service
public class CognitoService {

    private final CognitoIdentityProviderClient cognitoClient;
    private final AwsProperties awsProperties;

    public CognitoService(CognitoIdentityProviderClient cognitoClient, AwsProperties awsProperties) {
        this.cognitoClient = cognitoClient;
        this.awsProperties = awsProperties;
    }

    /**
     * Creates a Cognito user pool for Bhukkad user authentication.
     */
    public String createUserPool(String poolName) {
        return cognitoClient.createUserPool(CreateUserPoolRequest.builder()
                .poolName(poolName)
                .build()).userPool().id();
    }

    /**
     * Lists all user pools.
     */
    public List<UserPoolDescriptionType> listUserPools() {
        return cognitoClient.listUserPools(ListUserPoolsRequest.builder()
                .maxResults(60)
                .build()).userPools();
    }
}
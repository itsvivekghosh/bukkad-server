package com.bhukkad.common.aws;

import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.*;

import java.util.List;

/**
 * AWS SSM (Systems Manager Parameter Store) service for configuration management.
 * <p>
 * Provides operations for managing parameters in AWS Systems Manager Parameter Store.
 * <p>
 * Use cases in Bhukkad:
 * - Store and retrieve configuration values for microservices
 * - Store feature flags
 * - Store database connection strings securely
 * <p>
 * Fully testable against Floci emulator locally.
 */
@Service
public class SsmService {

    private final SsmClient ssmClient;

    public SsmService(SsmClient ssmClient) {
        this.ssmClient = ssmClient;
    }

    /**
     * Puts a parameter into Parameter Store.
     * <p>
     * Example usage:
     * <pre>
     *   ssmService.putParameter("/bhukkad/config/db-host", "db.example.com", "Database host", ParameterType.String);
     *   ssmClient.putSecureParameter("/bhukkad/secrets/db-password", "secret123", "Database password", ParameterType.SECURE_STRING);
     * </ul>
     */
    public PutParameterResponse putParameter(String name, String value, String description, ParameterType type) {
        return ssmClient.putParameter(PutParameterRequest.builder()
                .name(name)
                .value(value)
                .description(description)
                .type(type)
                .build());
    }

    /**
     * Gets a parameter value by name.
     * <p>
     * Example usage:
     * <pre>
     *   String dbHost = ssmClient.getParameter("/bhukkad/config/db-host", false);
     * </ul>
     */
    public String getParameter(String name) {
        return ssmClient.getParameter(GetParameterRequest.builder()
                .name(name)
                .withDecryption(true)
                .build()).parameter().value();
    }

    /**
     * Gets multiple parameters by names.
     * <p>
     * Example usage:
     * <pre>
     *   List&lt;Parameter&gt; params = ssmClient.getParameters(List.of("/a", "/b"), true);
     * </ul>
     */
    public GetParametersResponse getParameters(List<String> names) {
        return ssmClient.getParameters(GetParametersRequest.builder()
                .names(names)
                .withDecryption(true)
                .build());
    }

    /**
     * Deletes a parameter.
     */
    public DeleteParameterResponse deleteParameter(String name) {
        return ssmClient.deleteParameter(DeleteParameterRequest.builder()
                .name(name)
                .build());
    }

    /**
     * Gets parameters by path.
     * <p>
     * Example usage:
     * <pre>
     *   List&lt;Parameter&gt; params = ssmClient.getParametersByPath("/bhukkad/config/", true);
     * </ul>
     */
    public GetParametersByPathResponse getParametersByPath(String path) {
        return ssmClient.getParametersByPath(GetParametersByPathRequest.builder()
                .path(path)
                .withDecryption(true)
                .recursive(true)
                .build());
    }
}
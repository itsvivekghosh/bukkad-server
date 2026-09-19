package com.bhukkad.common.loadtest;

import java.net.URI;

/**
 * Interface for HTTP clients used by the API load tester.
 * This abstraction avoids a direct dependency on Spring Web's RestTemplate,
 * allowing the load test utility to work with different HTTP client implementations.
 */
public interface LoadTestHttpClient {

    /**
     * Executes a GET request and returns the response body.
     * @param uri The URI to request
     * @param responseType The expected response type
     * @param <T> The response type
     * @return The response body
     */
    <T> T get(URI uri, Class<T> responseType);

    /**
     * Executes a POST request and returns the response body.
     * @param uri The URI to request
     * @param body The request body
     * @param responseType The expected response type
     * @param <T> The response type
     * @return The response body
     */
    <T> T post(URI uri, Object body, Class<T> responseType);

    /**
     * Executes a PUT request.
     * @param uri The URI to request
     * @param body The request body
     */
    void put(URI uri, Object body);

    /**
     * Executes a DELETE request.
     * @param uri The URI to request
     */
    void delete(URI uri);
}
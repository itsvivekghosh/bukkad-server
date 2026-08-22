package com.bhukkad.contract;

import au.com.dius.pact.consumer.MockServer;
import au.com.dius.pact.consumer.dsl.PactDslJsonBody;
import au.com.dius.pact.consumer.dsl.PactDslWithProvider;
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt;
import au.com.dius.pact.consumer.junit5.PactTestFor;
import au.com.dius.pact.core.model.PactSpecVersion;
import au.com.dius.pact.core.model.RequestResponsePact;
import au.com.dius.pact.core.model.annotations.Pact;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Consumer-driven contract test.
 *
 * <p>The "consumer" here is the mobile/partner client of the Bhukkad public
 * API. It records its expectations for {@code GET /api/v1/cuisines} as a Pact
 * file ({@code target/pacts/}) that the provider side then verifies against
 * the real controller, so an API change cannot silently break clients.
 */
@ExtendWith(PactConsumerTestExt.class)
@PactTestFor(providerName = "BhukkadCuisineApi", pactVersion = PactSpecVersion.V3)
class CuisineApiConsumerPactTest {

    @Pact(consumer = "BhukkadMobileApp", provider = "BhukkadCuisineApi")
    public RequestResponsePact cuisineListContract(PactDslWithProvider builder) {
        PactDslJsonBody body = new PactDslJsonBody()
                .booleanType("success", true)
                .stringType("message");
        body.minArrayLike("data", 1, 10)
                .stringType("name")
                .stringType("description")
                .closeArray();
        body.stringType("traceId")
                .stringType("spanId")
                .stringType("requestId");
        return builder
                .given("active cuisines exist")
                .uponReceiving("a request for the active cuisine list")
                .path("/api/v1/cuisines")
                .method("GET")
                .willRespondWith()
                .status(200)
                .matchHeader("Content-Type", "application/json.*")
                .body(body)
                .toPact();
    }

    @Test
    @PactTestFor(pactMethod = "cuisineListContract")
    void cuisineListMatchesContract(MockServer mockServer) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(mockServer.getUrl() + "/api/v1/cuisines"))
                .GET()
                .build();

        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"success\":true"));
        assertTrue(response.body().contains("\"data\""));
        // The response must carry the trace/span correlation fields every API
        // response includes (they are part of the consumer contract).
        assertTrue(response.body().contains("\"traceId\""));
    }
}

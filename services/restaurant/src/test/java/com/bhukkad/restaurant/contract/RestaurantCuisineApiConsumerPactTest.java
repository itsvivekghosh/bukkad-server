package com.bhukkad.restaurant.contract;

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
 * Consumer-driven contract test (P4→P7 gate) for the restaurant service.
 *
 * <p>The consumer is the mobile/partner client of the Bhukkad public API. The
 * contract mirrors the monolith's {@code CuisineApiConsumerPactTest} so the
 * same client expectation now also applies to the extracted restaurant service
 * ({@code GET /api/v1/cuisines}). Running this test writes a pact file to
 * {@code target/pacts/} which a provider verification (restaurant controller)
 * later validates.</p>
 */
@ExtendWith(PactConsumerTestExt.class)
@PactTestFor(providerName = "BhukkadRestaurantCuisineApi", pactVersion = PactSpecVersion.V3)
class RestaurantCuisineApiConsumerPactTest {

    @Pact(consumer = "BhukkadMobileApp", provider = "BhukkadRestaurantCuisineApi")
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
    }
}
package com.bhukkad.social.contract;

import au.com.dius.pact.consumer.MockServer;
import au.com.dius.pact.consumer.dsl.PactDslWithProvider;
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt;
import au.com.dius.pact.consumer.junit5.PactTestFor;
import au.com.dius.pact.core.model.PactSpecVersion;
import au.com.dius.pact.core.model.RequestResponsePact;
import au.com.dius.pact.core.model.annotations.Pact;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Consumer-driven contract test for the Order service API.
 *
 * <p>This test defines the contract for how the Social service consumes
 * the Order service's create order endpoint. Running this test generates
 * a pact file that the Order service can use for provider verification.</p>
 */
@ExtendWith(PactConsumerTestExt.class)
@PactTestFor(providerName = "BhukkadOrderService", pactVersion = PactSpecVersion.V3)
class OrderServiceConsumerPactTest {

    @Pact(consumer = "BhukkadSocialService", provider = "BhukkadOrderService")
    public RequestResponsePact createOrderContract(PactDslWithProvider builder) {
        return builder
                .given("restaurant exists and has menu items")
                .uponReceiving("a request to create an order from social post")
                .path("/api/v1/orders")
                .method("POST")
                .headers("Content-Type", "application/json")
                .willRespondWith()
                .status(201)
                .toPact();
    }

    @Test
    @PactTestFor(pactMethod = "createOrderContract")
    void createOrderMatchesContract(MockServer mockServer) {
        RestTemplate restTemplate = new RestTemplate();
        String url = mockServer.getUrl() + "/api/v1/orders";
        String requestBody = "{\"customerId\":10,\"restaurantId\":100,\"items\":[{\"menuItemId\":200,\"name\":\"Burger\",\"unitPrice\":12.99,\"quantity\":2}]}";
        
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> request = new HttpEntity<>(requestBody, headers);
        
        var response = restTemplate.postForEntity(url, request, String.class);
        assertEquals(201, response.getStatusCodeValue());
    }
}

package com.bhukkad.support.client;

import com.bhukkad.common.security.ServiceJwtAuthTokenProvider;
import com.bhukkad.support.dto.OrderDetailDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class OrderServiceClient {

  private static final Logger log = LoggerFactory.getLogger(OrderServiceClient.class);

  private final RestClient restClient;
  private final String orderServiceUrl;

  public OrderServiceClient(RestClient.Builder restClientBuilder,
                            @Value("${app.services.order.url:http://order:8092}") String orderServiceUrl,
                            ServiceJwtAuthTokenProvider authTokenProvider) {
    this.restClient = restClientBuilder
        .baseUrl(orderServiceUrl)
        .defaultHeaders(headers -> {
          headers.setContentType(MediaType.APPLICATION_JSON);
          String token = authTokenProvider.serviceToken();
          if (token != null) {
            headers.setBearerAuth(token);
          }
        })
        .build();
    this.orderServiceUrl = orderServiceUrl;
  }

   /**
    * Get order details by orderId for dispute auto-resolution.
    *
    * @param orderId the order ID
    * @return OrderDetailDto containing the order details, or null if not found or error
    */
   public OrderDetailDto getOrderDetails(Long orderId) {
     try {
       ResponseEntity<OrderDetailDto> response = restClient.get()
           .uri("/api/v1/orders/{orderId}/details", orderId)
           .retrieve()
           .toEntity(OrderDetailDto.class);
       return response.getBody();
     } catch (Exception e) {
       log.error("Failed to get order details for orderId={}: {}", orderId, e.getMessage());
       return null;
     }
   }
}

package com.bhukkad.support.infrastructure.client;

import com.bhukkad.common.error.UpstreamUnavailableException;
import com.bhukkad.common.security.ServiceJwtAuthTokenProvider;
import com.bhukkad.support.dto.OrderDetailDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class OrderServiceClient {

  private static final Logger log = LoggerFactory.getLogger(OrderServiceClient.class);

  private final RestClient restClient;
  private final String orderServiceUrl;

  public OrderServiceClient(RestClient.Builder restClientBuilder,
                            @Value("${app.services.order.url:http://order:8080}") String orderServiceUrl,
                            ServiceJwtAuthTokenProvider authTokenProvider) {
    this.restClient = restClientBuilder
        .baseUrl(orderServiceUrl)
        .defaultHeaders(headers -> {
          headers.setContentType(MediaType.APPLICATION_JSON);
          String token = authTokenProvider.serviceToken();
          if (token != null) {
            headers.set("X-Service-Token", token);
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

   /** Response body of the order-service ownership oracle. */
   public record OrderCustomerRef(Long customerId) {
   }

   /**
    * Ownership oracle (audit CRITICAL-IDOR-1): resolves the customerId of an
    * order via the service-to-service contract
    * {@code GET /api/v1/internal/orders/{orderId}/customer} so dispute filing
    * can verify the filer owns the order before persisting anything.
    *
    * <p>Error contract: a genuine 404 resolves to {@code null} ("order does
    * not exist"); any other failure — timeout, connection refused, 5xx —
    * raises {@link UpstreamUnavailableException} so callers fail CLOSED on
    * the authorization decision instead of mistaking a mesh outage for a
    * missing order.</p>
    */
   public Long getOrderCustomerId(Long orderId) {
     try {
       ResponseEntity<OrderCustomerRef> response = restClient.get()
           .uri("/api/v1/internal/orders/{orderId}/customer", orderId)
           .retrieve()
           .toEntity(OrderCustomerRef.class);
       OrderCustomerRef body = response.getBody();
       return body == null ? null : body.customerId();
     } catch (HttpClientErrorException.NotFound notFound) {
       return null;
     } catch (RestClientException e) {
       log.error("Ownership oracle unavailable for orderId={}: {}", orderId, e.getMessage());
       throw new UpstreamUnavailableException("order", e);
     }
   }
}

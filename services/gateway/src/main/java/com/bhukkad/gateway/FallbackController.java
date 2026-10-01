package com.bhukkad.gateway;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Mono;

/**
 * Circuit-breaker fallback endpoint.
 *
 * <p>Two defects were fixed here, both of which made an upstream outage
 * invisible to clients and to monitoring:
 *
 * <ol>
 *   <li><b>GET-only mapping.</b> Spring Cloud Gateway's {@code CircuitBreaker}
 *       filter forwards the original request to {@code forward:/fallback/{service}}
 *       <em>preserving its method</em>. With a {@code @GetMapping}, any broken
 *       route answered POST/PUT/DELETE with {@code 405 Method Not Allowed} and an
 *       empty body — which reads as "this endpoint does not exist" rather than
 *       "this dependency is down", sending everyone hunting a routing bug during
 *       an incident. Every method is mapped now.</li>
 *   <li><b>HTTP 200 with an error message.</b> Without {@code @ResponseStatus}
 *       the handler returned {@code 200 OK} carrying
 *       {@code {"message":"... temporarily unavailable"}}. Every client, retry
 *       policy, SLO burn-rate alert and error-rate dashboard saw a perfectly
 *       healthy request: a fully open breaker was invisible and success metrics
 *       were inflated by failing calls. The status must be 503.</li>
 * </ol>
 */
@RestController
class FallbackController {

    @RequestMapping(
            value = "/fallback/{service}",
            method = {
                    RequestMethod.GET, RequestMethod.HEAD, RequestMethod.POST,
                    RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE,
                    RequestMethod.OPTIONS
            },
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Mono<ServiceUnavailableResponse> fallback(@PathVariable String service) {
        String message = service + " service is temporarily unavailable. Please retry.";
        return Mono.just(new ServiceUnavailableResponse(message));
    }

    record ServiceUnavailableResponse(
            String message
    ) {
    }
}

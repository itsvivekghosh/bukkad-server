package com.bhukkad.gateway;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Mono;

@RestController
class FallbackController {

    @GetMapping(value = "/fallback/{service}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ServiceUnavailableResponse> fallback(@PathVariable String service) {
        String message = service + " service is temporarily unavailable. Please retry.";
        return Mono.just(new ServiceUnavailableResponse(message));
    }

    record ServiceUnavailableResponse(
            String message
    ) {
    }
}

package com.bhukkad.admin.analytics.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Supplies the {@link RestClient.Builder} used by the internal mesh clients
 * (identity / delivery / order proxies) in the analytics and support slices.
 *
 * <p>Spring Boot's own {@code RestClientAutoConfiguration} is gated on
 * {@code NotReactiveWebApplicationCondition} and is not evaluated for every
 * context this module builds, so the injection point had no candidate and every
 * analytics context failed to start with "No qualifying bean of type
 * 'org.springframework.web.client.RestClient$Builder'". Declaring the builder
 * here is equivalent to {@code RestClient.builder()} and is what those clients
 * already do with it (base URL + default headers).
 */
@Configuration(proxyBeanMethods = false)
public class RestClientBuilderConfig {

    @Bean
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder();
    }
}

package com.bhukkad.common.web;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI documentation configuration.
 *
 * <p>SpringDoc auto-configures the Swagger UI and OpenAPI JSON endpoints.
 * This class customizes the API metadata and exposes all controller paths
 * under the service's base package.</p>
 *
 * <p>Conditional on {@link GroupedOpenApi} being on the classpath: services
 * that want Swagger UI add {@code springdoc-openapi-starter-webmvc-ui} as
 * their own dependency. Platform-lib does not pull springdoc in (it is
 * declared {@code optional} so the gateway, which is reactive, is not
 * forced onto springdoc/servlet APIs).</p>
 */
@Configuration
@ConditionalOnClass(GroupedOpenApi.class)
public class OpenApiConfig {

    @Bean
    public OpenAPI bhukkadOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Bhukkad API")
                        .description("Microservices API documentation")
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("Bhukkad Platform Team")
                                .url("https://github.com/itsvivekghosh/bhukkad"))
                        .license(new License()
                                .name("Proprietary")
                                .url("https://bhukkad.com")));
    }

    @Bean
    public GroupedOpenApi publicApi() {
        return GroupedOpenApi.builder()
                .group("public")
                .pathsToMatch("/api/**", "/actuator/**")
                .build();
    }
}

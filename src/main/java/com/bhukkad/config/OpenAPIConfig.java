package com.bhukkad.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenAPIConfig {

    @Value("${server.port:8080}")
    private String serverPort;

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(buildInfo())
                .servers(buildServers())
                .components(buildComponents())
                .addSecurityItem(new SecurityRequirement().addList("Bearer_JWT"))
                .tags(buildTags());
    }

    private Info buildInfo() {
        return new Info()
                .title("Bhukkad Food Delivery API")
                .version("1.0.0")
                .description("""
                        # Bhukkad Food Delivery Platform API
                        
                        Complete REST API for the Bhukkad food delivery platform.
                        
                        ## Authentication
                        - Register a new account using `/api/auth/register`
                        - Login using `/api/auth/login` to get JWT token
                        - Click **Authorize** button and enter: `Bearer <your-token>`
                        
                        ## Roles
                        - **CUSTOMER** - Browse restaurants, order food
                        - **RESTAURANT_OWNER** - Manage restaurant and menu
                        - **DELIVERY_AGENT** - Handle deliveries
                        - **ADMIN** - Platform administration
                        """)
                .contact(new Contact()
                        .name("Bhukkad Support")
                        .email("support@bhukkad.com")
                        .url("https://bhukkad.com"))
                .license(new License()
                        .name("Apache 2.0")
                        .url("https://www.apache.org/licenses/LICENSE-2.0.html"));
    }

    private List<Server> buildServers() {
        return List.of(
                new Server()
                        .url("http://localhost:" + serverPort)
                        .description("Local Development Server"),
                new Server()
                        .url("https://api.bhukkad.com")
                        .description("Production Server")
        );
    }

    private Components buildComponents() {
        return new Components()
                .addSecuritySchemes("Bearer_JWT", new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .in(SecurityScheme.In.HEADER)
                        .name("Authorization")
                        .description("Enter JWT token received from /api/auth/login"));
    }

    private List<Tag> buildTags() {
        return List.of(
                new Tag().name("Authentication").description("Register, Login, Password Management"),
                new Tag().name("Customer").description("Customer Profile and Address Management"),
                new Tag().name("Restaurant").description("Restaurant Management"),
                new Tag().name("Menu").description("Menu Categories and Items Management"),
                new Tag().name("Cart").description("Shopping Cart Operations"),
                new Tag().name("Order").description("Order Placement and Tracking"),
                new Tag().name("Delivery").description("Delivery Agent Operations"),
                new Tag().name("Review").description("Restaurant Reviews and Ratings"),
                new Tag().name("Health").description("Server Health Checks")
        );
    }
}
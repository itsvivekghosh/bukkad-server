package com.bhukkad.common.security;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@org.springframework.boot.context.properties.EnableConfigurationProperties(PlatformJwtProperties.class)
public class TestPlatformJwtProperties {
    public static void main(String[] args) {
        System.setProperty("app.auth.jwt.secret", "0123456789abcdef0123456789abcdef");
        System.setProperty("app.auth.jwt.issuer", "bhukkad-identity");
        System.setProperty("app.auth.jwt.hmac-grace-enabled", "true");
        
        ConfigurableApplicationContext context = SpringApplication.run(TestPlatformJwtProperties.class, new String[0]);
        
        PlatformJwtProperties props = context.getBean(PlatformJwtProperties.class);
        
        System.out.println("Secret: " + props.secret());
        System.out.println("Issuer: " + props.issuer());
        System.out.println("HMAC Grace Enabled: " + props.hmacGraceEnabled());
        System.out.println("HMAC Grace: " + props.hmacGrace());
        System.out.println("JWKS URL: " + props.jwksUrl());
        System.out.println("Enabled: " + props.enabled());
        
        context.close();
    }
}

package com.bhukkad.common.security;

import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.StandardEnvironment;

public class TestPropsMaven {
    public static void main(String[] args) {
        System.setProperty("app.auth.jwt.secret", "0123456789abcdef0123456789abcdef");
        System.setProperty("app.auth.jwt.issuer", "bhukkad-identity");
        System.setProperty("app.auth.jwt.hmac-grace-enabled", "true");
        
        StandardEnvironment env = new StandardEnvironment();
        Binder binder = Binder.get(env);
        var result = binder.bind("app.auth.jwt", PlatformJwtProperties.class);
        
        if (result.isBound()) {
            PlatformJwtProperties props = result.get();
            System.out.println("Secret: " + props.secret());
            System.out.println("Issuer: " + props.issuer());
            System.out.println("HMAC Grace Enabled: " + props.hmacGraceEnabled());
            System.out.println("HMAC Grace: " + props.hmacGrace());
            System.out.println("JWKS URL: " + props.jwksUrl());
            System.out.println("Enabled: " + props.enabled());
        } else {
            System.out.println("NOT BOUND!");
        }
    }
}

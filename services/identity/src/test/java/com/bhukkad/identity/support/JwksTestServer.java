package com.bhukkad.identity.support;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * Minimal in-process HTTP server that publishes a JWKS document.
 *
 * <p>Exists so identity's integration tests can point
 * {@code app.auth.jwt.jwks-url} at a stable address. The service validates its
 * own issued tokens by fetching keys over HTTP, and the tests run on a
 * random port, so the yml default ({@code http://localhost:8081}) cannot work.
 *
 * <p>Uses the JDK's built-in {@link HttpServer} — no extra dependency.
 */
final class JwksTestServer {

    private final HttpServer server;
    private final String jwksJson;

    private JwksTestServer(HttpServer server, String jwksJson) {
        this.server = server;
        this.jwksJson = jwksJson;
    }

    static JwksTestServer start(RSAKey rsaKey, String keyId) {
        try {
            RSAKey publicKey = new RSAKey.Builder(rsaKey.toRSAPublicKey())
                    .keyID(keyId)
                    .build();
            String json = new JWKSet(publicKey).toString();

            HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            JwksTestServer instance = new JwksTestServer(http, json);
            http.createContext("/.well-known/jwks.json", exchange -> {
                byte[] body = instance.jwksJson.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            http.start();
            return instance;
        } catch (com.nimbusds.jose.JOSEException e) {
            throw new IllegalStateException("failed to build JWKS test server key", e);
        } catch (IOException e) {
            throw new IllegalStateException("failed to start JWKS test server", e);
        }
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }
}

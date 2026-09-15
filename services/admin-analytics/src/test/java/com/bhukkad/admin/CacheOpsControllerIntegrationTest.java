package com.bhukkad.admin;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * Integration tests for {@link CacheOpsController} using MockMvc against the
 * real admin-analytics Spring Security + JWT stack.
 *
 * <p>Covers public endpoints, ADMIN-gated clear, and auth edge cases:
 * missing token, wrong role, expired token, invalid signature, missing scope.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.auth.jwt.secret=0123456789abcdef0123456789abcdef")
@AutoConfigureMockMvc
class CacheOpsControllerIntegrationTest extends AbstractAdminPostgresTest {

    @Autowired
    private MockMvc mockMvc;

    // ==================== HELPERS ====================

    private String bearerToken(String scope, long expirySeconds) throws JOSEException {
        String secret = "0123456789abcdef0123456789abcdef";
        Date now = new Date();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(String.valueOf(System.currentTimeMillis() / 1000))
                .claim("email", "test@bhukkad.test")
                .claim("scope", scope)
                .issueTime(now)
                .expirationTime(new Date(now.getTime() + expirySeconds * 1000))
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
        return "Bearer " + jwt.serialize();
    }

    // ==================== PUBLIC ENDPOINTS ====================

    @Test
    void health_returnsUpAndProviderInfo() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/cache/health"))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.provider").value("caffeine-local"));
    }

    @Test
    void stats_returnsCacheStatistics() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/cache/stats"))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.enabled").exists())
                .andExpect(jsonPath("$.estimatedSize").exists())
                .andExpect(jsonPath("$.hits").exists())
                .andExpect(jsonPath("$.misses").exists())
                .andExpect(jsonPath("$.caffeineHitRate").exists());
    }

    // ==================== AUTHENTICATED ENDPOINTS ====================

    @Test
    void clear_withoutAuth_returnsUnauthorized() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.delete("/api/v1/cache/clear"))
                .andExpect(MockMvcResultMatchers.status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void clear_withAdminToken_returnsOk() throws Exception {
        String token = bearerToken("admin", 3600);

        mockMvc.perform(MockMvcRequestBuilders.delete("/api/v1/cache/clear")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(jsonPath("$.message").value("cache cleared"));
    }

    @Test
    void clear_withNonAdminToken_returnsForbidden() throws Exception {
        String token = bearerToken("customer", 3600);

        mockMvc.perform(MockMvcRequestBuilders.delete("/api/v1/cache/clear")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(MockMvcResultMatchers.status().isForbidden());
    }

    @Test
    void clear_withExpiredToken_returnsUnauthorized() throws Exception {
        String token = bearerToken("admin", -10); // expired 10s ago

        mockMvc.perform(MockMvcRequestBuilders.delete("/api/v1/cache/clear")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(MockMvcResultMatchers.status().isUnauthorized());
    }

    @Test
    void clear_withInvalidSignature_returnsUnauthorized() throws Exception {
        // Build a token signed with a different secret
        String wrongSecret = "wrongsecretwrongsecretwrongsecret"; // 32 bytes
        Date now = new Date();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(String.valueOf(System.currentTimeMillis() / 1000))
                .claim("email", "evil@bhukkad.test")
                .claim("scope", "admin")
                .issueTime(now)
                .expirationTime(new Date(now.getTime() + 3600_000))
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(wrongSecret.getBytes(StandardCharsets.UTF_8)));
        String token = jwt.serialize();

        mockMvc.perform(MockMvcRequestBuilders.delete("/api/v1/cache/clear")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(MockMvcResultMatchers.status().isUnauthorized());
    }

    @Test
    void clear_withMissingScope_returnsUnauthorized() throws Exception {
        // Token with no scope claim: the user is authenticated but has no
        // authorities. Depending on the security filter ordering, this may
        // surface as 401 (empty context) or 403 (access denied without role).
        String secret = "0123456789abcdef0123456789abcdef";
        Date now = new Date();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("42")
                .issueTime(now)
                .expirationTime(new Date(now.getTime() + 3600_000))
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
        String token = jwt.serialize();

        mockMvc.perform(MockMvcRequestBuilders.delete("/api/v1/cache/clear")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(MockMvcResultMatchers.status().isUnauthorized());
    }
}

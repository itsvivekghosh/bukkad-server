package com.bhukkad.identity.api;

import com.bhukkad.identity.security.RsaSigningKeys;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;

/**
 * Serves identity's public signing keys (RS256 cutover, ADR-004 step 1).
 *
 * <p>Every service's {@code PlatformJwtValidator} fetches this endpoint (via
 * {@code JWKS_URL}) to verify identity-issued tokens. Public keys ONLY — the
 * JWK is rendered from the public half of the keypair, so the private
 * exponent never leaves the signing key bean. Responses carry a 1-hour
 * {@code Cache-Control: public, max-age=3600} header: validators keep their
 * own stale-while-revalidate cache, and this header keeps intermediate caches
 * honest without delaying key rotation past the validator refresh cadence.</p>
 */
@RestController
public class JwksController {

    /** Validator caches refresh hourly; mirrored here for intermediaries. */
    private static final Duration JWKS_CACHE_TTL = Duration.ofHours(1);

    private final RsaSigningKeys rsaKeys;

    public JwksController(RsaSigningKeys rsaKeys) {
        this.rsaKeys = rsaKeys;
    }

    @GetMapping(value = {"/.well-known/jwks.json", "/well-known/jwks.json"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> jwks() {
        Map<String, Object> body = rsaKeys.publicJwks().toJSONObject();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(JWKS_CACHE_TTL).cachePublic())
                .body(body);
    }
}

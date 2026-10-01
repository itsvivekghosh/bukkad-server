package com.bhukkad.admin.analytics.security;

import com.bhukkad.common.security.PlatformJwtValidator;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins why {@link CacheOpsControllerIntegrationTest} could not authenticate a
 * validly minted HS256 token: is the validator even enabled in this slice, and
 * does it accept the exact token shape those tests produce?
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.auth.jwt.secret=0123456789abcdef0123456789abcdef",
        "app.auth.jwt.hmac-grace-enabled=true"
})
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
class PlatformJwtValidatorSliceTest extends com.bhukkad.admin.analytics.AbstractAdminPostgresTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";

    @Autowired
    private PlatformJwtValidator validator;

    @Autowired
    private org.springframework.context.ApplicationContext applicationContext;

    @Test
    void validatorIsEnabledInThisSlice() {
        assertThat(validator.isEnabled())
                .as("secret is set, so the platform validator must be active")
                .isTrue();
    }

    @Test
    void platformFilterBeanIsRegistered() {
        assertThat(applicationContext.getBeanNamesForType(com.bhukkad.common.security.PlatformJwtAuthFilter.class))
                .as("SecurityConfig adds this filter to the chain only when the bean exists")
                .isNotEmpty();
    }

    @Test
    void mintingTheCacheOpsTokenAuthenticates() throws Exception {
        Date now = new Date();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(String.valueOf(now.getTime() / 1000))
                .claim("email", "test@bhukkad.test")
                .claim("scope", "customer")
                .issueTime(now)
                .expirationTime(new Date(now.getTime() + 3600_000L))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        assertThat(validator.validate(jwt.serialize()))
                .as("a valid HS256 token must produce a principal")
                .isPresent();
    }
}

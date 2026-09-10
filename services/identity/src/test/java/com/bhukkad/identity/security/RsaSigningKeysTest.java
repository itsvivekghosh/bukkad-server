package com.bhukkad.identity.security;

import com.nimbusds.jose.jwk.JWKSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** W1-AUTH deliverable 1: key provisioning precedence + prod fail-fast. */
class RsaSigningKeysTest {

    @TempDir
    Path tempDir;

    private static String pemOf(PrivateKey key) {
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(key.getEncoded());
        return "-----BEGIN PRIVATE KEY-----\n" + base64 + "\n-----END PRIVATE KEY-----\n";
    }

    private static String roundTripPem(String kid) {
        // Sign with a provisional key just to get an RSA private key out.
        try {
            return pemOf(new RsaSigningKeys(
                    new RsaKeyProperties(null, null, null, kid), false).signingKey().toRSAPrivateKey());
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String generatePem() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return pemOf(generator.generateKeyPair().getPrivate());
    }

    @Test
    void inlinePem_takesPrecedence() {
        String pem = roundTripPem("kid-inline");
        RsaSigningKeys keys = new RsaSigningKeys(
                new RsaKeyProperties(pem, null, null, "kid-inline"), false);

        assertThat(keys.signingKey().getKeyID()).isEqualTo("kid-inline");
        assertThat(keys.publicJwks().getKeys()).hasSize(1);
    }

    @Test
    void pemFile_loaded_whenPresent() throws Exception {
        Path pemFile = tempDir.resolve("k.pem");
        Files.writeString(pemFile, generatePem());

        RsaSigningKeys keys = new RsaSigningKeys(
                new RsaKeyProperties(null, pemFile.toString(), null, null), false);

        assertThat(keys.signingKey().getKeyID()).isNotBlank();
    }

    @Test
    void pemFile_missing_inStrictProfile_failsBoot() {
        Path missing = tempDir.resolve("nope.pem");
        assertThatThrownBy(() -> new RsaSigningKeys(
                new RsaKeyProperties(null, missing.toString(), null, null), true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing/unreadable");
    }

    @Test
    void noKeys_inStrictProfile_failsBoot() {
        assertThatThrownBy(() -> new RsaSigningKeys(
                new RsaKeyProperties(null, null, null, null), true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_PRIVATE_KEY_PEM");
    }

    @Test
    void noKeys_inDevProfile_generatesAndPersists() throws Exception {
        Path keyDir = tempDir.resolve("keys");
        RsaSigningKeys keys = new RsaSigningKeys(
                new RsaKeyProperties(null, null, keyDir.toString(), null), false);

        assertThat(keys.signingKey().toPrivateKey()).isNotNull();
        assertThat(Files.isRegularFile(keyDir.resolve("private-key.pem"))).isTrue();

        // Restart: the persisted key is reused (same JWKS, same kid).
        RsaSigningKeys restarted = new RsaSigningKeys(
                new RsaKeyProperties(null, null, keyDir.toString(), null), false);
        assertThat(restarted.signingKey().getKeyID()).isEqualTo(keys.signingKey().getKeyID());
        assertThat(restarted.publicJwks().toJSONObject())
                .isEqualTo(keys.publicJwks().toJSONObject());
    }

    @Test
    void publicJwks_exposesOnlyPublicKeys() {
        RsaSigningKeys keys = new RsaSigningKeys(
                new RsaKeyProperties(null, null, null, "kid-pub"), false);
        JWKSet jwks = keys.publicJwks();

        assertThat(jwks.getKeys()).hasSize(1);
        assertThat(jwks.getKeys().get(0).isPrivate()).isFalse();
    }

    @Test
    void pkcs1Pem_rejectedWithClearMessage() {
        String pkcs1 = "-----BEGIN RSA PRIVATE KEY-----\nAAAA\n-----END RSA PRIVATE KEY-----";
        assertThatThrownBy(() -> new RsaSigningKeys(
                new RsaKeyProperties(pkcs1, null, null, null), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PKCS#8");
    }
}

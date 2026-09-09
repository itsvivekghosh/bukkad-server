package com.bhukkad.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configurable password encoder properties.
 */
@Data
@ConfigurationProperties(prefix = "app.security.password-encoder")
public class PasswordEncoderProperties {

    /**
     * Hashing algorithm: "argon2" (default) or "bcrypt".
     */
    private String algorithm = "argon2";

    private Argon2 argon2 = new Argon2();
    private Bcrypt bcrypt = new Bcrypt();
    private AuthCache authCache = new AuthCache();

    @Data
    public static class Argon2 {
        private int saltLength = 16;
        private int hashLength = 32;
        private int parallelism = 1;
        private int memoryKib = 65536; // 64 MiB
        private int iterations = 3;
    }

    @Data
    public static class Bcrypt {
        private int strength = 12;
    }

    @Data
    public static class AuthCache {
        /**
         * Enable/disable authentication result caching.
         */
        private boolean enabled = true;
        /**
         * Maximum number of cached authentication results.
         */
        private long maximumSize = 10000;
        /**
         * Time-to-live for cached results in seconds.
         */
        private long expireAfterWriteSeconds = 30;
    }
}

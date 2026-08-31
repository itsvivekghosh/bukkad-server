package com.bhukkad.common.cache;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CacheKeyGeneratorTest {

    @Test
    void generatesDeterministicKey() {
        assertThat(CacheKeyGenerator.of("restaurant:menu", 42L))
                .isEqualTo(CacheKeyGenerator.of("restaurant:menu", 42L));
    }

    @Test
    void differentInputs_differentKeys() {
        assertThat(CacheKeyGenerator.of("order", 1L)).isNotEqualTo(CacheKeyGenerator.of("order", 2L));
    }

    @Test
    void keyIsBoundedLength() {
        String longArg = "x".repeat(10_000);
        String key = CacheKeyGenerator.of(longArg, "payload");
        assertThat(key.length()).isEqualTo(64);
    }

    @Test
    void emptyParts_throws() {
        assertThatThrownBy(() -> CacheKeyGenerator.of()).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void allPresent_guardsNulls() {
        assertThat(CacheKeyGenerator.allPresent(1L, "a")).isTrue();
        assertThat(CacheKeyGenerator.allPresent(1L, null)).isFalse();
    }
}

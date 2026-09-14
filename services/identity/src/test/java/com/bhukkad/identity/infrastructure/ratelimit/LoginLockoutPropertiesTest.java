package com.bhukkad.identity.infrastructure.ratelimit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Non-positive knobs must snap to safe production defaults (compact ctor). */
class LoginLockoutPropertiesTest {

    @Test
    void nonPositiveValues_fallBackToDefaults() {
        var props = new LoginLockoutProperties(0, -1, -300, 0, -86_400);
        assertThat(props.threshold()).isEqualTo(5);
        assertThat(props.failureWindowSeconds()).isEqualTo(900);
        assertThat(props.baseLockSeconds()).isEqualTo(300);
        assertThat(props.maxLockSeconds()).isEqualTo(3600);
        assertThat(props.strikesTtlSeconds()).isEqualTo(86_400);
    }

    @Test
    void explicitValues_arePreserved() {
        var props = new LoginLockoutProperties(3, 60, 15, 900, 86_400);
        assertThat(props.threshold()).isEqualTo(3);
        assertThat(props.failureWindowSeconds()).isEqualTo(60);
        assertThat(props.baseLockSeconds()).isEqualTo(15);
        assertThat(props.maxLockSeconds()).isEqualTo(900);
        assertThat(props.strikesTtlSeconds()).isEqualTo(86_400);
    }

    @Test
    void factoryDefaults() {
        var d = LoginLockoutProperties.defaults();
        assertThat(d.threshold()).isEqualTo(5);
        assertThat(d.baseLockSeconds()).isEqualTo(300);
    }
}

package com.bhukkad.common.security;

import reactor.blockhound.BlockHound;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Test helper: installs BlockHound exactly once per surefire JVM (the agent
 * is preloaded via {@code -javaagent}; this activates the detection). The
 * parent surefire run reuses one fork for the whole module, so every
 * BlockHound-based test class shares this guard instead of re-installing.
 */
final class BlockHoundSupport {

    private static final AtomicBoolean INSTALLED = new AtomicBoolean();

    private BlockHoundSupport() {
    }

    static void installOnce() {
        if (INSTALLED.compareAndSet(false, true)) {
            BlockHound.install();
        }
    }
}

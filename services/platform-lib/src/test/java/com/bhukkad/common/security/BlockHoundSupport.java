package com.bhukkad.common.security;

import reactor.blockhound.BlockHound;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Test helper: installs BlockHound exactly once per surefire JVM (the agent
 * is preloaded via {@code -javaagent}; this activates the detection). The
 * parent surefire run reuses one fork for the whole module, so every
 * BlockHound-based test class shares this guard instead of re-installing.
 *
 * <p>BlockHound 1.0.8.RELEASE supports up to JDK 21. On newer JDKs the
 * agent is not preloaded (see {@code platform-lib/pom.xml}) and this
 * method becomes a no-op so the BlockHound-specific tests can skip
 * themselves gracefully.</p>
 */
public final class BlockHoundSupport {

    private static final AtomicBoolean INSTALLED = new AtomicBoolean();
    private static final boolean SUPPORTED;

    static {
        int version = Runtime.version().feature();
        SUPPORTED = version <= 21;
    }

    private BlockHoundSupport() {
    }

    public static void installOnce() {
        if (!SUPPORTED) {
            return;
        }
        if (INSTALLED.compareAndSet(false, true)) {
            BlockHound.install();
        }
    }

    public static boolean isSupported() {
        return SUPPORTED;
    }
}

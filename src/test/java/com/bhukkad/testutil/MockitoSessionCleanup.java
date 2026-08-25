package com.bhukkad.testutil;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.mockito.Mockito;

/**
 * Global Mockito hygiene: clears inline-mock state after every test method.
 *
 * <p>The Mockito inline mock-maker shares process-wide instrumentation state.
 * On some JDK builds (GitHub Actions Temurin 17) a leaked static/inline mock
 * from one test class silently poisons the session for later classes in the
 * same fork — surfacing as {@code UnnecessaryStubbingException} and "Wanted but
 * not invoked" verify failures in unrelated tests (RedisCacheServiceTest,
 * OutboxEventProcessorTest) even though every individual test passes in
 * isolation. Resetting inline mocks after each method restores a clean session
 * and makes every test deterministic regardless of ordering.</p>
 *
 * <p>Registered for auto-detection via {@code junit-platform.properties}
 * ({@code junit.jupiter.extensions.autodetection.enabled=true}).</p>
 */
public class MockitoSessionCleanup implements AfterEachCallback {

    @Override
    public void afterEach(ExtensionContext context) {
        Mockito.framework().clearInlineMocks();
    }
}

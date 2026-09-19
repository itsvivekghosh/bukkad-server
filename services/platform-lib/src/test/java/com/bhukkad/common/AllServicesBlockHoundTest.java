package com.bhukkad.common;

import com.bhukkad.common.security.BlockHoundSupport;
import org.junit.jupiter.api.Assumptions;

/**
 * BlockHound scan across all {@code com.bhukkad} packages, including
 * service modules (order, payment, delivery, gateway, etc.).
 *
 * <p>This test is most meaningful when run from the repository root
 * ({@code mvn test}) so all service classes are on the test classpath.
 * When run in isolation ({@code -pl services/platform-lib test}) the
 * service packages are absent and the scan trivially passes.</p>
 */
class AllServicesBlockHoundTest extends BlockHoundTestBase {

    @Override
    protected String packagePrefix() {
        return "com.bhukkad";
    }

    @Override
    void scannedReactiveMethods_neverBlockOnEventLoopThreads() {
        Assumptions.assumeTrue(
                BlockHoundSupport.isSupported()
                        && getClass().getClassLoader().getResource("com/bhukkad/order") != null,
                "BlockHound not supported or service classes not on classpath");
        super.scannedReactiveMethods_neverBlockOnEventLoopThreads();
    }
}

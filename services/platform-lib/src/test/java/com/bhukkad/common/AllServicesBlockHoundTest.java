package com.bhukkad.common;

import com.bhukkad.common.security.BlockHoundSupport;
import org.junit.jupiter.api.Assumptions;

/**
 * BlockHound scan across every {@code com.bhukkad} class visible on the test
 * classpath, so it runs in every reactor module build without preconditioning
 * on a particular service module being present.
 *
 * <p>When run from a single module the scan covers that module's own classes
 * (platform-lib's shared infrastructure); service modules extend
 * {@link BlockHoundTestBase} for the same guarantee over their code. Only
 * BlockHound support itself can skip this test (see
 * {@link BlockHoundSupport}).</p>
 */
class AllServicesBlockHoundTest extends BlockHoundTestBase {

    @Override
    protected String packagePrefix() {
        return "com.bhukkad";
    }

    @Override
    void scannedReactiveMethods_neverBlockOnEventLoopThreads() {
        Assumptions.assumeTrue(
                BlockHoundSupport.isSupported(),
                "BlockHound not supported on JDK " + Runtime.version().feature());
        super.scannedReactiveMethods_neverBlockOnEventLoopThreads();
    }
}

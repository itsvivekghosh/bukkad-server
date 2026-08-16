package com.bhukkad.regression;

import org.junit.platform.suite.api.SelectPackages;
import org.junit.platform.suite.api.Suite;

/**
 * Complete Regression Test Suite
 * 
 * Runs all controller and service regression tests across the application.
 * 
 * Usage:
 *   mvn test -Dtest=RegressionTestSuite
 */
@Suite
@SelectPackages({
        "com.bhukkad.controller",
        "com.bhukkad.service",
        "com.bhukkad.repository",
        "com.bhukkad.security",
        "com.bhukkad.cache",
        "com.bhukkad.order",
        "com.bhukkad.payment",
        "com.bhukkad.delivery",
        "com.bhukkad.restaurant",
        "com.bhukkad.membership",
        "com.bhukkad.referral",
        "com.bhukkad.review",
        "com.bhukkad.coupon",
        "com.bhukkad.fraud",
        "com.bhukkad.inventory",
        "com.bhukkad.notification",
        "com.bhukkad.wallet",
        "com.bhukkad.zone",
        "com.bhukkad.promotion",
        "com.bhukkad.settlement",
        "com.bhukkad.storage",
        "com.bhukkad.outbox",
        "com.bhukkad.idempotency",
        "com.bhukkad.ratelimit",
        "com.bhukkad.metrics",
        "com.bhukkad.cluster",
        "com.bhukkad.datasource",
        "com.bhukkad.support",
        "com.bhukkad.timeline",
        "com.bhukkad.live",
        "com.bhukkad.feed",
        "com.bhukkad.logging",
        "com.bhukkad.util",
})
public class RegressionTestSuite {
    // Test suite runner - includes all regression-tagged tests
}

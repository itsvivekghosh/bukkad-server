package com.bhukkad.common.scan;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * G-6 unbounded-scan guard (PRODUCTION-READINESS-AUDIT-GUIDE.md §G-6): marks a
 * repository {@code findAll()} / {@code findAllBy*()} call-site as a reviewed,
 * deliberate full read.
 *
 * <p>Unbounded repository scans are the V-04 class of defect: a table that
 * grows with production traffic turns an innocent listing endpoint into an
 * O(table) memory/latency event. New call-sites must page or bound the read;
 * the few legitimate whole-table reads (small bounded reference datasets,
 * cache rebuilds over bounded data, SQL-paged listings) carry this annotation
 * with a reason string.
 *
 * <p>Enforced in CI by {@code scripts/ci/full-scan-guard.py}: every
 * {@code .findAll(} / {@code .findAllByX(} call-site under the service
 * modules' {@code src/main} trees must sit in a method or class annotated
 * with {@code @AllowFullScan(reason = "...")}, or the build fails.
 *
 * <p>Removing or widening an annotated site requires updating its reason —
 * the annotation is the review record, not a rubber stamp.
 */
@Documented
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface AllowFullScan {

    /**
     * Why this full read is safe: what bounds the row count (small reference
     * dataset, SQL-side paging, cache rebuild over bounded data) or which
     * tracked follow-up removes the scan.
     */
    String reason();
}

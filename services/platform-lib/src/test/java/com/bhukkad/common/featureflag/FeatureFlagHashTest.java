package com.bhukkad.common.featureflag;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shared edge/service bucketing: stable 0–99 buckets and the rollout decision
 * matrix (percent null/0/100, disabled global, anonymous subject).
 */
class FeatureFlagHashTest {

    @Test
    void bucket_isStableAndWithinRange() {
        int b1 = FeatureFlagHash.bucket("route.new-ui", 42L);
        int b2 = FeatureFlagHash.bucket("route.new-ui", 42L);
        assertThat(b1).isEqualTo(b2);
        for (long id : new long[]{0L, 1L, 999999L, Long.MAX_VALUE, Long.MIN_VALUE, -7L}) {
            int bucket = FeatureFlagHash.bucket("f", id);
            assertThat(bucket).as("id=%d", id).isBetween(0, 99);
        }
    }

    @Test
    void bucket_differsAcrossKeysAndSubjects() {
        assertThat(FeatureFlagHash.bucket("a", 1L)).isNotEqualTo(FeatureFlagHash.bucket("b", 1L));
        assertThat(FeatureFlagHash.bucket("a", 1L)).isNotEqualTo(FeatureFlagHash.bucket("a", 2L));
    }

    @Test
    void nullPercent_usesGlobalSwitch() {
        assertThat(FeatureFlagHash.inRollout("f", 5L, null, true)).isTrue();
        assertThat(FeatureFlagHash.inRollout("f", 5L, null, false)).isFalse();
    }

    @Test
    void disabledGlobal_shortCircuitsAnyPercent() {
        assertThat(FeatureFlagHash.inRollout("f", 5L, 100, false)).isFalse();
        assertThat(FeatureFlagHash.inRollout("f", 1L, 7, false)).isFalse();
    }

    @Test
    void fullRollout_enablesEveryoneIncludingAnonymous() {
        assertThat(FeatureFlagHash.inRollout("f", null, 100, true)).isTrue();
        assertThat(FeatureFlagHash.inRollout("f", 9L, 100, true)).isTrue();
    }

    @Test
    void unknownSubject_adoptsGlobalFlagRegardlessOfPercent() {
        // SUSPECTED SRC/MAIN BUG: the javadoc says “uid unknown → only 100 %
        // activates”, but the implementation short-circuits to globalEnabled
        // for ANY percent: an anonymous caller therefore falls INSIDE every
        // partial rollout of an enabled flag. Assertion follows code.
        assertThat(FeatureFlagHash.inRollout("f", null, 50, true)).isTrue();
        assertThat(FeatureFlagHash.inRollout("f", null, 1, true)).isTrue();
        assertThat(FeatureFlagHash.inRollout("f", null, 50, false)).isFalse();
    }

    @Test
    void zeroOrNegativePercent_disablesEveryone() {
        assertThat(FeatureFlagHash.inRollout("f", 5L, 0, true)).isFalse();
        assertThat(FeatureFlagHash.inRollout("f", 5L, -10, true)).isFalse();
    }

    @Test
    void partialRollout_followsSharedBucket() {
        String key = "checkout.paylater";
        long id = 123456L;
        int bucket = FeatureFlagHash.bucket(key, id);
        assertThat(FeatureFlagHash.inRollout(key, id, bucket + 1, true))
                .as("percent just above bucket enables").isTrue();
        assertThat(FeatureFlagHash.inRollout(key, id, bucket, true))
                .as("percent at bucket excludes (strictly-less-than)").isFalse();
    }
}

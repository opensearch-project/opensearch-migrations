/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.http.retries;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class TargetRetryPacingTest {
    private static final long SECOND = 1_000_000_000L;

    private final AtomicLong now = new AtomicLong(0);

    private TargetRetryPacing paced(double random, double ratio, double floor) {
        return TargetRetryPacing.paced(
            Duration.ofMillis(100),
            Duration.ofSeconds(30),
            ratio,
            floor,
            () -> random,
            now::get
        );
    }

    @Test
    void equalJitterKeepsHalfTheBackoffAndRandomizesTheRest() {
        var low = paced(0.0, 0, 1_000_000);
        var high = paced(0.999_999, 0, 1_000_000);

        Assertions.assertEquals(Duration.ofMillis(50), low.delayBeforeRetry(1));
        Assertions.assertEquals(Duration.ofMillis(100), low.delayBeforeRetry(2));
        Assertions.assertTrue(high.delayBeforeRetry(1).compareTo(Duration.ofMillis(100)) < 0);
        Assertions.assertTrue(high.delayBeforeRetry(1).compareTo(Duration.ofMillis(99)) > 0);
    }

    @Test
    void backoffIsCappedAtTheMaximumDelay() {
        var pacing = paced(0.0, 0, 1_000_000);

        Assertions.assertEquals(Duration.ofSeconds(15), pacing.delayBeforeRetry(20));
        Assertions.assertEquals(Duration.ofSeconds(15), pacing.delayBeforeRetry(Integer.MAX_VALUE));
    }

    @Test
    void deterministicPacingReturnsTheExactCeiling() {
        var pacing = TargetRetryPacing.deterministic(Duration.ofMillis(100), Duration.ofSeconds(300));

        Assertions.assertEquals(Duration.ofMillis(100), pacing.delayBeforeRetry(1));
        Assertions.assertEquals(Duration.ofMillis(200), pacing.delayBeforeRetry(2));
        Assertions.assertEquals(Duration.ofSeconds(300), pacing.delayBeforeRetry(64));
    }

    @Test
    void retriesBeyondTheFloorBudgetAreSpreadAtTheFloorRate() {
        var pacing = paced(0.0, 0, 10);

        // One second of unspent budget at 10 per second, plus the current slot, is available immediately.
        for (var i = 0; i < 11; i++) {
            Assertions.assertEquals(Duration.ofMillis(50), pacing.delayBeforeRetry(1), "retry " + i);
        }
        Assertions.assertEquals(0, pacing.budgetDeferredRetries());
        var deferred = new long[5];
        for (var i = 0; i < deferred.length; i++) {
            deferred[i] = pacing.delayBeforeRetry(1).toMillis();
        }
        Assertions.assertArrayEquals(new long[] { 100, 200, 300, 400, 500 }, deferred);
        Assertions.assertEquals(5, pacing.budgetDeferredRetries());
    }

    @Test
    void firstAttemptsRaiseTheRetryBudget() {
        var pacing = paced(0.0, 0.5, 1);
        for (var i = 0; i < 200; i++) {
            pacing.firstAttemptStarted();
        }
        now.addAndGet(SECOND);
        pacing.delayBeforeRetry(1);

        // 200 first attempts per second at a ratio of 0.5 allow 100 retries per second.
        var delays = new long[110];
        for (var i = 0; i < delays.length; i++) {
            delays[i] = pacing.delayBeforeRetry(1).toMillis();
        }
        Assertions.assertEquals(50, delays[90]);
        Assertions.assertEquals(100, delays[109]);
    }

    @Test
    void unusedBudgetDoesNotAccumulateBeyondOneSecond() {
        var pacing = paced(0.0, 0, 10);
        now.addAndGet(60 * SECOND);

        for (var i = 0; i < 11; i++) {
            pacing.delayBeforeRetry(1);
        }
        Assertions.assertEquals(Duration.ofMillis(100), pacing.delayBeforeRetry(1));
    }

    @Test
    void defaultPolicyReportsOnlyFirstAttemptsToItsPacing() {
        var pacing = paced(0.0, 1.0, 1);
        var policy = new OpenSearchDefaultRetry(new BulkItemErrorClassifier(), pacing);
        for (var i = 0; i < 50; i++) {
            policy.targetAttemptStarted(1);
            policy.targetAttemptStarted(2);
        }
        now.addAndGet(SECOND);
        policy.retryDelay(1);

        // 50 first attempts per second at a ratio of 1 allow about 50 retries before one is deferred;
        // counting the 50 second attempts too would have allowed about 100.
        var undeferred = 0;
        while (policy.retryDelay(1).equals(Duration.ofMillis(50))) {
            undeferred++;
        }
        Assertions.assertTrue(undeferred >= 50 && undeferred <= 53, "undeferred retries: " + undeferred);
    }
}

/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.opensearch.migrations.replay.identity.PartitionGenerationId;

import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ReplayCatchUpPacerTest {
    private static final Instant NOW = Instant.parse("2026-10-11T12:00:00Z");
    private static final TopicPartition PARTITION = new TopicPartition("capture", 7);

    private final ReplayCatchUpPacer pacer = ReplayCatchUpPacer.bounded(2.0, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void aGenerationThatStartsBehindReplaysItsBacklogAtTheCatchUpFactor() {
        var generation = new PartitionGenerationId(PARTITION, 0);
        var anchor = NOW.minusSeconds(60);

        Assertions.assertEquals(NOW, pacer.pace(generation, anchor));
        Assertions.assertEquals(NOW.plusSeconds(10), pacer.pace(generation, anchor.plusSeconds(20)));
        Assertions.assertEquals(NOW.plusSeconds(30), pacer.pace(generation, anchor.plusSeconds(60)));
        // The backlog is absorbed once the schedule reaches twice the starting lag past the anchor.
        Assertions.assertEquals(NOW.plusSeconds(60), pacer.pace(generation, anchor.plusSeconds(120)));
        Assertions.assertEquals(NOW.plusSeconds(90), pacer.pace(generation, anchor.plusSeconds(150)));
    }

    @Test
    void aGenerationThatStartsOnOrAheadOfScheduleIsNeverDelayed() {
        var generation = new PartitionGenerationId(PARTITION, 0);
        var anchor = NOW.plusSeconds(5);

        Assertions.assertEquals(anchor, pacer.pace(generation, anchor));
        Assertions.assertEquals(anchor.plusSeconds(30), pacer.pace(generation, anchor.plusSeconds(30)));
    }

    @Test
    void aRequestScheduledBeforeTheAnchorKeepsItsPacedOrder() {
        var generation = new PartitionGenerationId(PARTITION, 0);
        pacer.pace(generation, NOW.minusSeconds(60));

        Assertions.assertEquals(
            NOW.minusSeconds(5),
            pacer.pace(generation, NOW.minusSeconds(70))
        );
    }

    @Test
    void aNewGenerationReanchorsAndAnOlderGenerationPassesThrough() {
        var first = new PartitionGenerationId(PARTITION, 0);
        var second = new PartitionGenerationId(PARTITION, 1);
        pacer.pace(first, NOW.minusSeconds(60));

        var secondAnchor = NOW.minusSeconds(10);
        Assertions.assertEquals(NOW, pacer.pace(second, secondAnchor));
        Assertions.assertEquals(NOW.plusSeconds(5), pacer.pace(second, secondAnchor.plusSeconds(10)));
        var oldGenerationTime = NOW.minusSeconds(30);
        Assertions.assertEquals(oldGenerationTime, pacer.pace(first, oldGenerationTime));
    }

    @Test
    void partitionsAreAnchoredIndependently() {
        var a = new PartitionGenerationId(PARTITION, 0);
        var b = new PartitionGenerationId(new TopicPartition("capture", 8), 0);
        pacer.pace(a, NOW.minusSeconds(60));

        Assertions.assertEquals(NOW.plusSeconds(1), pacer.pace(b, NOW.plusSeconds(1)));
        Assertions.assertEquals(NOW.plusSeconds(10), pacer.pace(a, NOW.minusSeconds(40)));
    }

    @Test
    void anUnboundedPacerLeavesTimesUnchanged() {
        var unbounded = ReplayCatchUpPacer.unbounded();
        var generation = new PartitionGenerationId(PARTITION, 0);
        var past = NOW.minus(Duration.ofHours(1));

        Assertions.assertEquals(past, unbounded.pace(generation, past));
    }

    @Test
    void aFactorOfOneOrLessIsRejected() {
        var clock = Clock.fixed(NOW, ZoneOffset.UTC);
        Assertions.assertThrows(IllegalArgumentException.class, () -> ReplayCatchUpPacer.bounded(1.0, clock));
        Assertions.assertThrows(IllegalArgumentException.class, () -> ReplayCatchUpPacer.bounded(Double.NaN, clock));
    }
}

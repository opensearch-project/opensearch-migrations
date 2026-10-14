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
import java.util.concurrent.ConcurrentHashMap;

import org.opensearch.migrations.replay.identity.PartitionGenerationId;

import lombok.NonNull;
import org.apache.kafka.common.TopicPartition;

/**
 * Bounds how fast a newly assigned partition generation catches up on work that is already overdue.
 *
 * <p>A generation that starts behind its replay schedule would otherwise send its whole backlog at once,
 * although the source spread those requests across the lag. The first scheduled time a generation asks
 * about anchors it to the current instant; afterwards a request scheduled {@code d} after the anchor is
 * sent no earlier than {@code d / catchUpFactor} after the anchor instant. The backlog therefore replays
 * {@code catchUpFactor} times faster than the normal schedule until the generation is back on schedule,
 * and from then on scheduled times pass through unchanged. A generation that starts on or ahead of
 * schedule is never delayed.
 *
 * <p>Thread-safe; every connection of a partition shares its anchor.
 */
public final class ReplayCatchUpPacer {
    public static final double DEFAULT_CATCH_UP_FACTOR = 2.0;

    // The warnings target compiler-generated members of this private anchor value.
    @SuppressWarnings({"java:S100", "java:S1172", "java:S1186"})
    private record Anchor(long generationSequence, Instant anchorInstant, Instant anchorScheduledTime) {}

    private final double catchUpFactor;
    private final Clock clock;
    private final ConcurrentHashMap<TopicPartition, Anchor> anchorsByPartition = new ConcurrentHashMap<>();

    private ReplayCatchUpPacer(double catchUpFactor, @NonNull Clock clock) {
        this.catchUpFactor = catchUpFactor;
        this.clock = clock;
    }

    /** Leaves every scheduled time unchanged. */
    public static ReplayCatchUpPacer unbounded() {
        return new ReplayCatchUpPacer(Double.POSITIVE_INFINITY, Clock.systemUTC());
    }

    /**
     * @param catchUpFactor how many times faster than the normal schedule overdue work replays; must be
     *     greater than 1, or infinite to disable pacing
     */
    public static ReplayCatchUpPacer bounded(double catchUpFactor, @NonNull Clock clock) {
        if (Double.isNaN(catchUpFactor) || catchUpFactor <= 1.0) {
            throw new IllegalArgumentException("catchUpFactor must be greater than 1");
        }
        return new ReplayCatchUpPacer(catchUpFactor, clock);
    }

    public Instant pace(@NonNull PartitionGenerationId generation, @NonNull Instant scheduledTime) {
        if (Double.isInfinite(catchUpFactor)) {
            return scheduledTime;
        }
        var anchor = anchorsByPartition.compute(generation.topicPartition(), (partition, existing) ->
            existing != null && existing.generationSequence() >= generation.localSequence()
                ? existing
                : new Anchor(generation.localSequence(), clock.instant(), scheduledTime)
        );
        if (anchor.generationSequence() != generation.localSequence()) {
            return scheduledTime;
        }
        var sinceAnchor = Duration.between(anchor.anchorScheduledTime(), scheduledTime);
        var paced = anchor.anchorInstant().plusNanos((long) (sinceAnchor.toNanos() / catchUpFactor));
        return paced.isAfter(scheduledTime) ? paced : scheduledTime;
    }
}

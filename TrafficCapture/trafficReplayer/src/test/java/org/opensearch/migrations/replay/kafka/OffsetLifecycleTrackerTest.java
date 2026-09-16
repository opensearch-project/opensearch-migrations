package org.opensearch.migrations.replay.kafka;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for OffsetLifecycleTracker — specifically the stale-head reaper
 * and the tolerance for already-reaped offsets.
 */
class OffsetLifecycleTrackerTest {

    private static Clock fixedClock(Instant instant) {
        return Clock.fixed(instant, ZoneId.of("UTC"));
    }

    // -------------------------------------------------------------------------
    // Bulk reap tests
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("reapStaleHead removes all consecutive stale offsets in one sweep")
    void reapStaleHead_bulkRemovesAllStaleOffsets() {
        var baseTime = Instant.parse("2026-01-01T00:00:00Z");
        var mutableClock = new AtomicReference<>(baseTime);
        var clock = new Clock() {
            @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return mutableClock.get(); }
        };

        var tracker = new OffsetLifecycleTracker(1, clock);

        // Add 5 offsets all at baseTime
        for (int i = 0; i < 5; i++) {
            tracker.add(100 + i, "conn-" + i);
        }
        Assertions.assertEquals(5, tracker.size());

        // Advance clock past the 5-minute threshold
        mutableClock.set(baseTime.plus(Duration.ofMinutes(6)));

        // Single reapStaleHead call should remove ALL 5 stale offsets
        var result = tracker.reapStaleHead(Duration.ofMinutes(5));
        Assertions.assertTrue(result.isPresent(), "Should return new commit offset");
        Assertions.assertEquals(105L, result.get(),
            "Commit offset should advance to cursorHighWatermark+1 (104+1=105)");
        Assertions.assertEquals(0, tracker.size(), "Queue should be empty after bulk reap");
    }

    @Test
    @DisplayName("reapStaleHead stops at first non-stale offset")
    void reapStaleHead_stopsAtFreshOffset() {
        var baseTime = Instant.parse("2026-01-01T00:00:00Z");
        var mutableClock = new AtomicReference<>(baseTime);
        var clock = new Clock() {
            @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return mutableClock.get(); }
        };

        var tracker = new OffsetLifecycleTracker(1, clock);

        // Add 3 offsets at baseTime (will be stale)
        tracker.add(100, "conn-A");
        tracker.add(101, "conn-B");
        tracker.add(102, "conn-C");

        // Advance clock by 3 minutes and add 2 more (will NOT be stale at 5min threshold)
        mutableClock.set(baseTime.plus(Duration.ofMinutes(3)));
        tracker.add(103, "conn-D");
        tracker.add(104, "conn-E");

        // Advance clock to 6 minutes past baseTime (offsets 100-102 are 6min old, 103-104 are 3min old)
        mutableClock.set(baseTime.plus(Duration.ofMinutes(6)));

        var result = tracker.reapStaleHead(Duration.ofMinutes(5));
        Assertions.assertTrue(result.isPresent());
        Assertions.assertEquals(103L, result.get(),
            "Should advance to 103 (first non-stale offset remaining)");
        Assertions.assertEquals(2, tracker.size(),
            "Queue should have 2 non-stale offsets remaining (103, 104)");
    }

    @Test
    @DisplayName("reapStaleHead returns empty when head is fresh")
    void reapStaleHead_returnsEmpty_whenHeadIsFresh() {
        var baseTime = Instant.parse("2026-01-01T00:00:00Z");
        var tracker = new OffsetLifecycleTracker(1, fixedClock(baseTime));
        tracker.add(100, "conn-A");

        // Head was just added — not stale
        var result = tracker.reapStaleHead(Duration.ofMinutes(5));
        Assertions.assertTrue(result.isEmpty(), "Should not reap a fresh offset");
        Assertions.assertEquals(1, tracker.size());
    }

    @Test
    @DisplayName("reapStaleHead returns empty on empty queue")
    void reapStaleHead_returnsEmpty_whenQueueIsEmpty() {
        var tracker = new OffsetLifecycleTracker(1);
        var result = tracker.reapStaleHead(Duration.ofMinutes(5));
        Assertions.assertTrue(result.isEmpty());
    }

    // -------------------------------------------------------------------------
    // Tolerance for already-reaped offsets
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("removeAndReturnNewHead tolerates already-reaped offset (queue empty)")
    void removeAndReturnNewHead_toleratesAlreadyReapedOffset_queueEmpty() {
        var tracker = new OffsetLifecycleTracker(1);
        tracker.add(100, "conn-A");

        // Simulate reaper removing it
        tracker.removeAndReturnNewHead(100);

        // Normal commit path tries to remove same offset — should NOT throw
        var result = tracker.removeAndReturnNewHead(100);
        Assertions.assertTrue(result.isEmpty(),
            "Should return empty for already-reaped offset, not throw");
    }

    @Test
    @DisplayName("removeAndReturnNewHead tolerates already-reaped offset (queue has other entries)")
    void removeAndReturnNewHead_toleratesAlreadyReapedOffset_queueNotEmpty() {
        var tracker = new OffsetLifecycleTracker(1);
        tracker.add(100, "conn-A");
        tracker.add(101, "conn-B");
        tracker.add(102, "conn-C");

        // Reaper removes head (100) — advances to 101
        var reaped = tracker.removeAndReturnNewHead(100);
        Assertions.assertTrue(reaped.isPresent());
        Assertions.assertEquals(101L, reaped.get());

        // Normal commit path tries to remove 100 again
        var duplicate = tracker.removeAndReturnNewHead(100);
        Assertions.assertTrue(duplicate.isEmpty(),
            "Should gracefully handle duplicate removal");

        // Remaining offsets still work normally
        var result101 = tracker.removeAndReturnNewHead(101);
        Assertions.assertTrue(result101.isPresent());
        Assertions.assertEquals(102L, result101.get());
    }

    // -------------------------------------------------------------------------
    // Bulk reap followed by late completion (the production race)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Bulk reap followed by late removeAndReturnNewHead is safe")
    void bulkReap_thenLateRemoval_isSafe() {
        var baseTime = Instant.parse("2026-01-01T00:00:00Z");
        var mutableClock = new AtomicReference<>(baseTime);
        var clock = new Clock() {
            @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return mutableClock.get(); }
        };

        var tracker = new OffsetLifecycleTracker(1, clock);
        tracker.add(100, "conn-A");
        tracker.add(101, "conn-B");
        tracker.add(102, "conn-C");

        // Advance past threshold and bulk-reap all
        mutableClock.set(baseTime.plus(Duration.ofMinutes(6)));
        var reaped = tracker.reapStaleHead(Duration.ofMinutes(5));
        Assertions.assertTrue(reaped.isPresent());
        Assertions.assertEquals(103L, reaped.get());
        Assertions.assertEquals(0, tracker.size());

        // Late completions arrive for all reaped offsets — must not throw
        Assertions.assertTrue(tracker.removeAndReturnNewHead(100).isEmpty());
        Assertions.assertTrue(tracker.removeAndReturnNewHead(101).isEmpty());
        Assertions.assertTrue(tracker.removeAndReturnNewHead(102).isEmpty());
    }

    // -------------------------------------------------------------------------
    // Basic functionality (sanity checks)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Normal add/remove flow works correctly")
    void normalFlow_addAndRemoveInOrder() {
        var tracker = new OffsetLifecycleTracker(1);
        tracker.add(10, "c1");
        tracker.add(11, "c2");
        tracker.add(12, "c3");

        // Remove head — should advance
        var r10 = tracker.removeAndReturnNewHead(10);
        Assertions.assertTrue(r10.isPresent());
        Assertions.assertEquals(11L, r10.get());

        // Remove non-head — no advance
        var r12 = tracker.removeAndReturnNewHead(12);
        Assertions.assertTrue(r12.isEmpty());

        // Remove new head (11) — advances past 12 (already removed) to hwm+1
        var r11 = tracker.removeAndReturnNewHead(11);
        Assertions.assertTrue(r11.isPresent());
        Assertions.assertEquals(13L, r11.get());
    }
}

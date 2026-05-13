package org.opensearch.migrations.bulkload.lucene.sidecar;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

import org.opensearch.migrations.bulkload.lucene.SourcelessSpillConfig;

import lombok.extern.slf4j.Slf4j;

/**
 * JVM-wide accounting of bytes-in-flight in spill files across all concurrent
 * {@link SidecarBuilder}s. Singleton because the disk is a single shared
 * resource: per-builder caps wouldn't catch the case where N fields × M
 * segments each individually fit but collectively exceed disk.
 *
 * <p>The cap is read once on first access from
 * {@link SourcelessSpillConfig#maxSpillBytes()} and stays fixed for the JVM's
 * lifetime — operators tune it via {@code -D} at worker startup, not at runtime.
 *
 * <p>Reservation is best-effort: a winning {@link #tryReserve(long)} commits
 * the bytes; subsequent {@link #release(long)} calls (in {@code SidecarBuilder.close()}
 * and on failure paths) make those bytes available to other builders. The cap
 * fires before disk fills, surfacing as {@link SpillBudgetExceededException} from
 * the offending {@code accept()} call. The segment Flux's {@code concatMapDelayError}
 * propagates that as a per-segment failure, freeing the segment's spill tree on
 * close — exactly the behavior the worker lease semantics want.
 */
@Slf4j
public final class SpillBudget {

    private static final AtomicLong BYTES_IN_FLIGHT = new AtomicLong();
    private static volatile long capBytes = -1L; // -1 = uninitialized
    private static volatile long freeSpaceFloorBytes = -1L;

    private SpillBudget() {}

    private static long cap() {
        long c = capBytes;
        if (c < 0L) {
            c = SourcelessSpillConfig.maxSpillBytes();
            capBytes = c;
        }
        return c;
    }

    private static long freeSpaceFloor() {
        long f = freeSpaceFloorBytes;
        if (f < 0L) {
            f = SourcelessSpillConfig.spillFreeSpaceMinBytes();
            freeSpaceFloorBytes = f;
        }
        return f;
    }

    /**
     * Reserve {@code bytes} from the global budget. Throws
     * {@link SpillBudgetExceededException} if the reservation would exceed the
     * configured cap, leaving the counter unchanged so the caller can choose
     * how to react. Callers MUST balance every successful reservation with a
     * matching {@link #release(long)} call (typically from {@code close()}).
     */
    public static void tryReserve(long bytes) throws SpillBudgetExceededException {
        if (bytes <= 0L) return;
        long limit = cap();
        if (limit == Long.MAX_VALUE) {
            BYTES_IN_FLIGHT.addAndGet(bytes);
            return;
        }
        long updated = BYTES_IN_FLIGHT.addAndGet(bytes);
        if (updated > limit) {
            // Roll back our delta so the in-flight number stays honest, then fail.
            BYTES_IN_FLIGHT.addAndGet(-bytes);
            throw new SpillBudgetExceededException(
                "RFS sourceless reconstruction would exceed configured spill budget"
                + " (" + SourcelessSpillConfig.MAX_SPILL_BYTES_PROP + "=" + limit
                + " bytes); aborting field build before disk fills."
                + " Current in-flight=" + (updated - bytes)
                + ", attempted reservation=" + bytes);
        }
    }

    /**
     * Probe the spill volume's usable space and abort if it has fallen below
     * the configured floor. Cheap enough to call at coarse granularity (e.g.
     * once per N MiB written) but not free — every call is a syscall.
     */
    public static void assertVolumeHeadroom(Path spillDir) throws SpillBudgetExceededException {
        long floor = freeSpaceFloor();
        if (floor <= 0L) return;
        long usable = SourcelessSpillConfig.usableSpace(spillDir);
        if (usable < floor) {
            throw new SpillBudgetExceededException(
                "RFS sourceless reconstruction spill volume below floor"
                + " (" + SourcelessSpillConfig.SPILL_FREE_SPACE_MIN_BYTES_PROP + "=" + floor
                + " bytes, usable=" + usable + " bytes, dir=" + spillDir
                + "); aborting field build before disk fills.");
        }
    }

    /** Return previously-reserved bytes to the budget. Idempotent on zero/negative input. */
    public static void release(long bytes) {
        if (bytes <= 0L) return;
        BYTES_IN_FLIGHT.addAndGet(-bytes);
    }

    /** Visible for testing. */
    public static long bytesInFlight() {
        return BYTES_IN_FLIGHT.get();
    }

    /**
     * Visible for testing. Resets cached config and the counter so a test can
     * exercise different {@code -D} values inside a single JVM. Production code
     * never calls this.
     */
    static void resetForTest() {
        BYTES_IN_FLIGHT.set(0L);
        capBytes = -1L;
        freeSpaceFloorBytes = -1L;
    }
}

package org.opensearch.migrations.bulkload.tracing;

import java.util.Locale;

import lombok.extern.slf4j.Slf4j;

/**
 * What this RFS worker process is doing now. A process works on one work item and exits, moving through the phases
 * in order (a phase may be skipped). The current phase is exported continuously as a gauge, one series per phase
 * (1 for the current one, 0 for the rest), so a dashboard can show how many workers are in each phase at any moment;
 * and each transition is logged at INFO with how long the previous phase took.
 */
@Slf4j
public final class WorkerPhase {
    public enum Phase {
        /** Reading configuration and connecting. */
        STARTING,
        /** Setting up work items and acquiring one. */
        ACQUIRING_WORK,
        /** Downloading the shard's files from the snapshot repository and unpacking them. */
        DOWNLOADING_SHARD,
        /** Reading documents and sending them to the target. */
        MIGRATING,
        /** Recording the work item as complete, or handing the rest of it to a successor. */
        COMPLETING,
        /** The process is exiting. */
        EXITED;

        public String label() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private static Phase current = Phase.STARTING;
    // The process started when the JVM did, not when this class was first used.
    private static long enteredNanos = jvmStartNanos();

    private WorkerPhase() {}

    private static long jvmStartNanos() {
        return System.nanoTime() - java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime() * 1_000_000;
    }

    public static synchronized Phase current() {
        return current;
    }

    /** Move to {@code phase}; does nothing if the process is already in it. */
    public static void enter(Phase phase) {
        Phase previous;
        long tookMillis;
        synchronized (WorkerPhase.class) {
            if (phase == current) {
                return;
            }
            long now = System.nanoTime();
            previous = current;
            tookMillis = (now - enteredNanos) / 1_000_000;
            current = phase;
            enteredNanos = now;
        }
        log.atInfo().setMessage("Worker phase: {} -> {} ({} took {} ms)")
            .addArgument(previous::label)
            .addArgument(phase::label)
            .addArgument(previous::label)
            .addArgument(tookMillis)
            .log();
    }

    /** For tests: start over from {@link Phase#STARTING}. */
    public static synchronized void reset() {
        current = Phase.STARTING;
        enteredNanos = jvmStartNanos();
    }
}

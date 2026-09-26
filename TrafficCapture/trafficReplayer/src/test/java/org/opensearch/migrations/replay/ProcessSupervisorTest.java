/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay;

// REBUILD-LIMBO(G11) -- nothing in this region is live yet. The baseline source remains
// recoverable through the final completeness sweep.
// REBUILD-LIMBO-START(G11)
/*

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ProcessSupervisorTest {

*/
// REBUILD-LIMBO-END(G11)
    /** Proves replayer LLD §8 and processing architecture §10.3's fatal-exit ladder. */
// REBUILD-LIMBO-START(G11)
/*
    @Test
    void armsTenMinuteWatchdogBeforeExitThenDumpsAndHaltsWithSameCode() {
        var events = new ArrayList<String>();
        var watchdogDelay = new AtomicReference<Duration>();
        var watchdogAction = new AtomicReference<Runnable>();
        var stderrBytes = new ByteArrayOutputStream();
        var supervisor = new ProcessSupervisor(
            exitCode -> events.add("exit:" + exitCode),
            exitCode -> events.add("halt:" + exitCode),
            (delay, action) -> {
                watchdogDelay.set(delay);
                watchdogAction.set(action);
                events.add("watchdog");
            },
            stream -> {
                events.add("thread-dump");
                stream.println("test thread dump");
            },
            new PrintStream(stderrBytes, false, StandardCharsets.UTF_8)
        );

        supervisor.terminate(89);

        Assertions.assertEquals(ProcessSupervisor.EXIT_WATCHDOG_LIMIT, watchdogDelay.get());
        Assertions.assertEquals(List.of("watchdog", "exit:89"), events);

        watchdogAction.get().run();

        Assertions.assertEquals(
            List.of("watchdog", "exit:89", "thread-dump", "halt:89"),
            events
        );
        var stderr = stderrBytes.toString(StandardCharsets.UTF_8);
        Assertions.assertTrue(stderr.contains("System.exit did not complete within PT10M"));
        Assertions.assertTrue(stderr.contains("test thread dump"));
    }
}

*/
// REBUILD-LIMBO-END(G11)

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ProcessSupervisorTest {

    @Test
    void firstFatalSignalStopsInputFlushesDiagnosticsAndStartsExit80() {
        var events = new ArrayList<String>();
        var watchdogDelay = new AtomicReference<Duration>();
        var watchdogAction = new AtomicReference<Runnable>();
        var stderrBytes = new ByteArrayOutputStream();
        var supervisor = new ProcessSupervisor(
            reason -> events.add("metric:" + reason.metricLabel()),
            signal -> events.add("stop:" + signal.owner() + ":" + signal.operation()),
            exitCode -> events.add("exit:" + exitCode),
            exitCode -> events.add("halt:" + exitCode),
            (delay, action) -> {
                watchdogDelay.set(delay);
                watchdogAction.set(action);
                events.add("watchdog");
            },
            stream -> {
                events.add("thread-dump");
                stream.println("test thread dump");
            },
            () -> events.add("diagnostic-flush"),
            new PrintStream(stderrBytes, false, StandardCharsets.UTF_8)
        );

        var accepted = new ProcessSupervisor.FatalSignal(
            ProcessSupervisor.Reason.EVENT_LOOP_TERMINATED,
            "target event loop worker 3",
            "termination future",
            new Error("event loop stopped")
        );
        supervisor.onFatal(accepted);
        supervisor.onFatal(new ProcessSupervisor.FatalSignal(
            ProcessSupervisor.Reason.UNEXPECTED_FATAL_ERROR,
            "replay intake owner",
            "owner loop",
            new Error("duplicate")
        ));

        Assertions.assertEquals(ProcessSupervisor.EXIT_WATCHDOG_LIMIT, watchdogDelay.get());
        Assertions.assertEquals(
            List.of(
                "watchdog",
                "stop:target event loop worker 3:termination future",
                "metric:event_loop_terminated",
                "diagnostic-flush",
                "exit:80"
            ),
            events
        );
        Assertions.assertSame(accepted, supervisor.firstFatalSignal().orElseThrow());
        Assertions.assertTrue(supervisor.fatalTerminationStarted());

        watchdogAction.get().run();

        Assertions.assertEquals(
            List.of(
                "watchdog",
                "stop:target event loop worker 3:termination future",
                "metric:event_loop_terminated",
                "diagnostic-flush",
                "exit:80",
                "thread-dump",
                "halt:80"
            ),
            events
        );
        var stderr = stderrBytes.toString(StandardCharsets.UTF_8);
        Assertions.assertTrue(stderr.contains("owner=target event loop worker 3"));
        Assertions.assertTrue(stderr.contains("operation=termination future"));
        Assertions.assertTrue(stderr.contains("System.exit did not complete within PT10M"));
        Assertions.assertTrue(stderr.contains("test thread dump"));
    }

    @Test
    void brokenFailureHooksCannotPreventExitOrWatchdogHalt() {
        var exits = new ArrayList<Integer>();
        var halts = new ArrayList<Integer>();
        var watchdogAction = new AtomicReference<Runnable>();
        var failure = new Error("owner failure");
        var supervisor = new ProcessSupervisor(
            reason -> {
                throw new IllegalStateException("metric broken");
            },
            signal -> {
                throw new IllegalStateException("stop broken");
            },
            exits::add,
            halts::add,
            (delay, action) -> {
                Assertions.assertFalse(delay.isNegative());
                Assertions.assertTrue(delay.compareTo(ProcessSupervisor.EXIT_WATCHDOG_LIMIT) <= 0);
                watchdogAction.set(action);
            },
            stream -> {
                throw new IllegalStateException("dump broken");
            },
            () -> {
                throw new IllegalStateException("flush broken");
            },
            new PrintStream(new ByteArrayOutputStream(), false, StandardCharsets.UTF_8)
        );

        supervisor.unexpectedOwnerFailure("Kafka source owner", "poll", failure);

        Assertions.assertEquals(List.of(ProcessSupervisor.UNEXPECTED_OWNER_EXIT_CODE), exits);
        Assertions.assertEquals(3, failure.getSuppressed().length);

        watchdogAction.get().run();

        Assertions.assertEquals(List.of(ProcessSupervisor.UNEXPECTED_OWNER_EXIT_CODE), halts);
    }
}

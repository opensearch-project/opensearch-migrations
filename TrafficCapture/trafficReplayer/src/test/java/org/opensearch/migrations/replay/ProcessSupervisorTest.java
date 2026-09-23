/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay;

// REBUILD-LIMBO(G11) -- nothing in this file is live yet. Javadoc is left outside the marked
// regions so it needs no escaping and keeps its blame; it documents code that is not compiled.
// Resolve each region to dead, keep, or refactor deliberately. If a member is deleted, delete its
// javadoc with it. See AGENTS.md section 8a.

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
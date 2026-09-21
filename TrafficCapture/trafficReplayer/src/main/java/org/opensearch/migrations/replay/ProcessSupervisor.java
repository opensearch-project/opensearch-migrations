/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay;

import java.io.PrintStream;
import java.time.Duration;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;

/**
 * Runs the controlled process-failure ladder.
 *
 * <p>The watchdog starts before {@link System#exit(int)} so shutdown hooks have a bounded ten-minute
 * opportunity to finish. If they do not, the watchdog writes a thread dump and terminates through
 * {@link Runtime#halt(int)} with the same reason-specific exit code.
 */
public final class ProcessSupervisor implements ReplayProcessFatalHandler.ProcessTerminator {
    public static final Duration EXIT_WATCHDOG_LIMIT = Duration.ofMinutes(10);

    @FunctionalInterface
    interface ExitInitiator {
        void exit(int exitCode);
    }

    @FunctionalInterface
    interface HaltTerminator {
        void halt(int exitCode);
    }

    @FunctionalInterface
    interface WatchdogStarter {
        void start(Duration delay, Runnable action);
    }

    @FunctionalInterface
    interface ThreadDumper {
        void dump(PrintStream stream);
    }

    private final ExitInitiator exitInitiator;
    private final HaltTerminator haltTerminator;
    private final WatchdogStarter watchdogStarter;
    private final ThreadDumper threadDumper;
    private final PrintStream errorStream;

    public ProcessSupervisor() {
        this(
            System::exit,
            Runtime.getRuntime()::halt,
            ProcessSupervisor::startWatchdogThread,
            ProcessSupervisor::dumpAllThreads,
            System.err
        );
    }

    ProcessSupervisor(
        ExitInitiator exitInitiator,
        HaltTerminator haltTerminator,
        WatchdogStarter watchdogStarter,
        ThreadDumper threadDumper,
        PrintStream errorStream
    ) {
        this.exitInitiator = Objects.requireNonNull(exitInitiator);
        this.haltTerminator = Objects.requireNonNull(haltTerminator);
        this.watchdogStarter = Objects.requireNonNull(watchdogStarter);
        this.threadDumper = Objects.requireNonNull(threadDumper);
        this.errorStream = Objects.requireNonNull(errorStream);
    }

    @Override
    public void terminate(int exitCode) {
        watchdogStarter.start(EXIT_WATCHDOG_LIMIT, () -> {
            try {
                errorStream.println(
                    "FATAL: System.exit did not complete within "
                        + EXIT_WATCHDOG_LIMIT
                        + "; writing a thread dump before Runtime.halt("
                        + exitCode
                        + ")"
                );
                threadDumper.dump(errorStream);
            } catch (Throwable dumpFailure) {
                try {
                    errorStream.println("FATAL: failed to write the process watchdog thread dump");
                    dumpFailure.printStackTrace(errorStream);
                } catch (Throwable ignored) {
                    // Runtime.halt must not depend on diagnostic output remaining functional.
                }
            } finally {
                try {
                    errorStream.flush();
                } finally {
                    haltTerminator.halt(exitCode);
                }
            }
        });
        exitInitiator.exit(exitCode);
    }

    private static void startWatchdogThread(Duration delay, Runnable action) {
        var watchdog = new Thread(() -> {
            try {
                Thread.sleep(delay.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            action.run();
        }, "replayer-fatal-exit-watchdog");
        watchdog.setDaemon(false);
        watchdog.start();
    }

    private static void dumpAllThreads(PrintStream stream) {
        stream.println("Full thread dump:");
        Thread.getAllStackTraces()
            .entrySet()
            .stream()
            .sorted(
                Comparator.comparing((Map.Entry<Thread, StackTraceElement[]> entry) -> entry.getKey().getName())
                    .thenComparingLong(entry -> entry.getKey().threadId())
            )
            .forEach(entry -> {
                var thread = entry.getKey();
                stream.println(
                    '"'
                        + thread.getName()
                        + "\" #"
                        + thread.threadId()
                        + " "
                        + thread.getState()
                );
                for (var frame : entry.getValue()) {
                    stream.println("\tat " + frame);
                }
                stream.println();
            });
    }
}

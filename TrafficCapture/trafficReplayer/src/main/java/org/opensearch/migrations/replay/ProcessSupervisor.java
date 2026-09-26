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
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import lombok.extern.slf4j.Slf4j;
import org.apache.logging.log4j.LogManager;

/**
 * Owns the process-failure boundary for the replay application.
 *
 * <p>The first unexpected owner failure wins. The watchdog is armed before any failure hook runs so a
 * non-responsive owner, diagnostic sink, or JVM shutdown hook cannot keep the process alive past the
 * documented bound.</p>
 */
@Slf4j
public final class ProcessSupervisor {
    public static final Duration EXIT_WATCHDOG_LIMIT = Duration.ofMinutes(10);
    public static final int EVENT_LOOP_EXIT_CODE = 80;
    public static final int UNEXPECTED_OWNER_EXIT_CODE = 89;

    public enum Reason {
        EVENT_LOOP_TERMINATED(
            EVENT_LOOP_EXIT_CODE,
            "event_loop_terminated",
            "FATAL: a required replayer event loop terminated"
        ),
        UNEXPECTED_FATAL_ERROR(
            UNEXPECTED_OWNER_EXIT_CODE,
            "unexpected_fatal_error",
            "FATAL: a replayer owner failed unexpectedly"
        );

        private final int exitCode;
        private final String metricLabel;
        private final String message;

        Reason(int exitCode, String metricLabel, String message) {
            this.exitCode = exitCode;
            this.metricLabel = metricLabel;
            this.message = message;
        }

        public int exitCode() {
            return exitCode;
        }

        public String metricLabel() {
            return metricLabel;
        }

        public String message() {
            return message;
        }
    }

    public record FatalSignal(
        Reason reason,
        String owner,
        String operation,
        Error failure
    ) {
        public FatalSignal {
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(operation, "operation");
            Objects.requireNonNull(failure, "failure");
        }
    }

    @FunctionalInterface
    public interface FailureSink {
        void onFatal(FatalSignal signal);
    }

    @FunctionalInterface
    public interface Metrics {
        Metrics NOOP = ignored -> {};

        void fatalFailure(Reason reason);
    }

    @FunctionalInterface
    public interface InputStopper {
        InputStopper NOOP = ignored -> {};

        void stopNewInput(FatalSignal signal);
    }

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

    private final Metrics metrics;
    private final InputStopper inputStopper;
    private final ExitInitiator exitInitiator;
    private final HaltTerminator haltTerminator;
    private final WatchdogStarter watchdogStarter;
    private final ThreadDumper threadDumper;
    private final Runnable diagnosticFlusher;
    private final PrintStream errorStream;
    private final AtomicReference<FatalSignal> firstFatal = new AtomicReference<>();

    public ProcessSupervisor(Metrics metrics, InputStopper inputStopper) {
        this(
            metrics,
            inputStopper,
            System::exit,
            Runtime.getRuntime()::halt,
            ProcessSupervisor::startWatchdogThread,
            ProcessSupervisor::dumpAllThreads,
            LogManager::shutdown,
            System.err
        );
    }

    ProcessSupervisor(
        Metrics metrics,
        InputStopper inputStopper,
        ExitInitiator exitInitiator,
        HaltTerminator haltTerminator,
        WatchdogStarter watchdogStarter,
        ThreadDumper threadDumper,
        Runnable diagnosticFlusher,
        PrintStream errorStream
    ) {
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.inputStopper = Objects.requireNonNull(inputStopper, "inputStopper");
        this.exitInitiator = Objects.requireNonNull(exitInitiator, "exitInitiator");
        this.haltTerminator = Objects.requireNonNull(haltTerminator, "haltTerminator");
        this.watchdogStarter = Objects.requireNonNull(watchdogStarter, "watchdogStarter");
        this.threadDumper = Objects.requireNonNull(threadDumper, "threadDumper");
        this.diagnosticFlusher = Objects.requireNonNull(diagnosticFlusher, "diagnosticFlusher");
        this.errorStream = Objects.requireNonNull(errorStream, "errorStream");
    }

    public void unexpectedOwnerFailure(String owner, String operation, Error failure) {
        onFatal(new FatalSignal(Reason.UNEXPECTED_FATAL_ERROR, owner, operation, failure));
    }

    public void eventLoopTerminated(String owner, String operation, Error failure) {
        onFatal(new FatalSignal(Reason.EVENT_LOOP_TERMINATED, owner, operation, failure));
    }

    public FailureSink failureSink() {
        return this::onFatal;
    }

    public void onFatal(FatalSignal signal) {
        Objects.requireNonNull(signal, "signal");
        if (!firstFatal.compareAndSet(null, signal)) {
            return;
        }

        watchdogStarter.start(EXIT_WATCHDOG_LIMIT, () -> watchdogExpired(signal));
        stopNewInput(signal);
        writeDiagnostics(signal);
        exitInitiator.exit(signal.reason().exitCode());
    }

    public boolean fatalTerminationStarted() {
        return firstFatal.get() != null;
    }

    public Optional<FatalSignal> firstFatalSignal() {
        return Optional.ofNullable(firstFatal.get());
    }

    private void stopNewInput(FatalSignal signal) {
        try {
            inputStopper.stopNewInput(signal);
        } catch (Throwable stopFailure) {
            signal.failure().addSuppressed(stopFailure);
        }
    }

    private void writeDiagnostics(FatalSignal signal) {
        try {
            metrics.fatalFailure(signal.reason());
        } catch (Throwable metricFailure) {
            signal.failure().addSuppressed(metricFailure);
        }

        try {
            log.atError()
                .setCause(signal.failure())
                .setMessage("{}; owner={}, operation={}")
                .addArgument(signal.reason().message())
                .addArgument(signal.owner())
                .addArgument(signal.operation())
                .log();
        } catch (Throwable loggingFailure) {
            signal.failure().addSuppressed(loggingFailure);
        }

        try {
            errorStream.println(
                signal.reason().message()
                    + "; owner="
                    + signal.owner()
                    + ", operation="
                    + signal.operation()
            );
            signal.failure().printStackTrace(errorStream);
        } catch (Throwable stderrFailure) {
            signal.failure().addSuppressed(stderrFailure);
        }

        try {
            diagnosticFlusher.run();
        } catch (Throwable flushFailure) {
            signal.failure().addSuppressed(flushFailure);
            try {
                errorStream.println("FATAL: diagnostic flush failed during replay termination");
                flushFailure.printStackTrace(errorStream);
            } catch (Throwable ignored) {
                // The watchdog remains responsible for eventual termination.
            }
        }

        try {
            errorStream.flush();
        } catch (Throwable flushFailure) {
            signal.failure().addSuppressed(flushFailure);
        }
    }

    private void watchdogExpired(FatalSignal signal) {
        try {
            errorStream.println(
                "FATAL: System.exit did not complete within "
                    + EXIT_WATCHDOG_LIMIT
                    + "; owner="
                    + signal.owner()
                    + ", operation="
                    + signal.operation()
                    + "; writing a thread dump before Runtime.halt("
                    + signal.reason().exitCode()
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
                haltTerminator.halt(signal.reason().exitCode());
            }
        }
    }

    private static void startWatchdogThread(Duration delay, Runnable action) {
        var watchdog = new Thread(() -> {
            try {
                Thread.sleep(delay.toMillis());
            } catch (InterruptedException interrupted) {
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

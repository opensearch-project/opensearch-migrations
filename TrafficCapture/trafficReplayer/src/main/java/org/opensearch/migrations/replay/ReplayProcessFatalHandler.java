package org.opensearch.migrations.replay;

// REBUILD-LIMBO(G5) -- nothing in this file is live yet. Javadoc is left outside the marked
// regions so it needs no escaping and keeps its blame; it documents code that is not compiled.
// Resolve each region to dead, keep, or refactor deliberately. If a member is deleted, delete its
// javadoc with it. See AGENTS.md section 8a.
// Cascade from the left-behind legacy set. Unresolved: (unresolved reference into the left-behind set; see build log). Carried byte-identical so the behaviour stays enumerable; its milestone strips the legacy references and un-marks it.
// Un-mark a member by deleting the delimiter lines around it and splitting this region; the
// code between them is verbatim, so blame survives. Read this before writing anything new

// REBUILD-LIMBO-START(G5)
/*

import java.io.PrintStream;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import lombok.extern.slf4j.Slf4j;
import org.apache.logging.log4j.LogManager;

*/
// REBUILD-LIMBO-END(G5)
/**
 * Emits the last process-fatal diagnostics and then terminates without entering normal shutdown.
 */
// REBUILD-LIMBO-START(G5)
/*
@Slf4j
public final class ReplayProcessFatalHandler implements RequestSenderOrchestrator.FatalReplayHandler {
    public enum Reason {
        EVENT_LOOP_TERMINATED(
            80,
            "event_loop_terminated",
            "FATAL: a replayer event loop terminated while its session was live; terminating the process"
        ),
        UNEXPECTED_FATAL_ERROR(
            89,
            "unexpected_fatal_error",
            "FATAL: the replayer encountered an unexpected process-fatal error; terminating the process"
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

    @FunctionalInterface
    public interface Metrics {
        void fatalFailure(Reason reason);
    }

    @FunctionalInterface
    public interface ProcessTerminator {
        void terminate(int exitCode);
    }

    @FunctionalInterface
    public interface ReasonClassifier {
        Reason classify(Error failure);
    }

    private final ReasonClassifier reasonClassifier;
    private final Metrics metrics;
    private final ProcessTerminator processTerminator;
    private final Runnable log4jFlusher;
    private final PrintStream errorStream;
    private final Consumer<Error> fatalShutdownSignaler;
    private final AtomicBoolean started = new AtomicBoolean();

    public ReplayProcessFatalHandler(
        Reason reason,
        Metrics metrics,
        ProcessTerminator processTerminator
    ) {
        this(ignored -> reason, metrics, processTerminator, LogManager::shutdown, System.err, ignored -> {});
    }

    ReplayProcessFatalHandler(
        Reason reason,
        Metrics metrics,
        ProcessTerminator processTerminator,
        Runnable log4jFlusher,
        PrintStream errorStream
    ) {
        this(ignored -> reason, metrics, processTerminator, log4jFlusher, errorStream, ignored -> {});
    }

    ReplayProcessFatalHandler(
        Reason reason,
        Metrics metrics,
        ProcessTerminator processTerminator,
        Runnable log4jFlusher,
        PrintStream errorStream,
        Consumer<Error> fatalShutdownSignaler
    ) {
        this(ignored -> reason, metrics, processTerminator, log4jFlusher, errorStream, fatalShutdownSignaler);
    }

    ReplayProcessFatalHandler(
        ReasonClassifier reasonClassifier,
        Metrics metrics,
        ProcessTerminator processTerminator,
        Runnable log4jFlusher,
        PrintStream errorStream,
        Consumer<Error> fatalShutdownSignaler
    ) {
        this.reasonClassifier = Objects.requireNonNull(reasonClassifier);
        this.metrics = Objects.requireNonNull(metrics);
        this.processTerminator = Objects.requireNonNull(processTerminator);
        this.log4jFlusher = Objects.requireNonNull(log4jFlusher);
        this.errorStream = Objects.requireNonNull(errorStream);
        this.fatalShutdownSignaler = Objects.requireNonNull(fatalShutdownSignaler);
    }

    @Override
    public void onFatal(Error failure) {
        Objects.requireNonNull(failure);
        if (!started.compareAndSet(false, true)) {
            return;
        }

        Reason reason;
        try {
            reason = Objects.requireNonNull(
                reasonClassifier.classify(failure),
                "fatal reason classifier returned null"
            );
        } catch (Throwable classifierFailure) {
            failure.addSuppressed(classifierFailure);
            reason = Reason.UNEXPECTED_FATAL_ERROR;
        }

        try {
            metrics.fatalFailure(reason);
        } catch (Throwable metricFailure) {
            failure.addSuppressed(metricFailure);
        }

        try {
            log.atError()
                .setCause(failure)
                .setMessage(reason.message())
                .log();
        } catch (Throwable loggingFailure) {
            failure.addSuppressed(loggingFailure);
        }

        try {
            errorStream.println(reason.message());
            failure.printStackTrace(errorStream);
        } catch (Throwable stderrFailure) {
            failure.addSuppressed(stderrFailure);
        }

        try {
            log4jFlusher.run();
        } catch (Throwable flushFailure) {
            failure.addSuppressed(flushFailure);
            try {
                errorStream.println("Log4j2 flush failed during fatal replayer termination:");
                flushFailure.printStackTrace(errorStream);
            } catch (Throwable ignored) {
                // Process termination must not depend on diagnostic output remaining functional.
            }
        }

        try {
            errorStream.flush();
        } catch (Throwable flushFailure) {
            failure.addSuppressed(flushFailure);
        }

        try {
            fatalShutdownSignaler.accept(failure);
        } catch (Throwable shutdownSignalFailure) {
            failure.addSuppressed(shutdownSignalFailure);
            try {
                errorStream.println("Fatal shutdown signaling failed before process termination:");
                shutdownSignalFailure.printStackTrace(errorStream);
                errorStream.flush();
            } catch (Throwable ignored) {
                // Process termination must not depend on diagnostic output remaining functional.
            }
        }

        processTerminator.terminate(reason.exitCode());
    }
}

*/
// REBUILD-LIMBO-END(G5)
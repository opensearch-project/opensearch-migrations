package org.opensearch.migrations.replay;

import java.io.PrintStream;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import lombok.extern.slf4j.Slf4j;
import org.apache.logging.log4j.LogManager;

/**
 * Emits the last process-fatal diagnostics and then terminates without entering normal shutdown.
 */
@Slf4j
public final class ReplayProcessFatalHandler implements RequestSenderOrchestrator.FatalReplayHandler {
    public enum Reason {
        EVENT_LOOP_TERMINATED(
            80,
            "event_loop_terminated",
            "FATAL: a replayer event loop terminated while its session was live; halting the process"
        ),
        UNEXPECTED_FATAL_ERROR(
            89,
            "unexpected_fatal_error",
            "FATAL: the replayer encountered an unexpected process-fatal error; halting the process"
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

    private final Reason reason;
    private final Metrics metrics;
    private final ProcessTerminator processTerminator;
    private final Runnable log4jFlusher;
    private final PrintStream errorStream;
    private final AtomicBoolean started = new AtomicBoolean();

    public ReplayProcessFatalHandler(
        Reason reason,
        Metrics metrics,
        ProcessTerminator processTerminator
    ) {
        this(reason, metrics, processTerminator, LogManager::shutdown, System.err);
    }

    ReplayProcessFatalHandler(
        Reason reason,
        Metrics metrics,
        ProcessTerminator processTerminator,
        Runnable log4jFlusher,
        PrintStream errorStream
    ) {
        this.reason = Objects.requireNonNull(reason);
        this.metrics = Objects.requireNonNull(metrics);
        this.processTerminator = Objects.requireNonNull(processTerminator);
        this.log4jFlusher = Objects.requireNonNull(log4jFlusher);
        this.errorStream = Objects.requireNonNull(errorStream);
    }

    @Override
    public void onFatal(Error failure) {
        Objects.requireNonNull(failure);
        if (!started.compareAndSet(false, true)) {
            return;
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
        } finally {
            processTerminator.terminate(reason.exitCode());
        }
    }
}

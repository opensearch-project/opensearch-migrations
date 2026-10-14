package org.opensearch.migrations.trafficcapture.proxyserver;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

/**
 * Implements the last-resort process boundary for compromised capture or unstable proxy state.
 * The first failure starts a non-daemon termination thread; later failures are ignored so several
 * failing connections cannot launch competing shutdown sequences.
 *
 * <p>Diagnostic logs are flushed on a separate daemon thread under a strict timeout, after which
 * the JVM is halted directly. This avoids depending on ordinary shutdown hooks or cooperative
 * executors that may themselves be deadlocked or corrupted, while still making a bounded attempt
 * to preserve the evidence explaining the exit.
 */
@Slf4j
final class CaptureFailureTerminator implements Consumer<Throwable> {
    private final int exitCode;
    private final Duration logFlushTimeout;
    private final Runnable flushLogs;
    private final IntConsumer haltProcess;
    private final AtomicBoolean started = new AtomicBoolean();

    CaptureFailureTerminator(
        int exitCode,
        Duration logFlushTimeout,
        @NonNull Runnable flushLogs,
        @NonNull IntConsumer haltProcess
    ) {
        this.exitCode = exitCode;
        this.logFlushTimeout = requirePositive(logFlushTimeout);
        this.flushLogs = flushLogs;
        this.haltProcess = haltProcess;
    }

    @Override
    public void accept(@NonNull Throwable failure) {
        if (!started.compareAndSet(false, true)) {
            return;
        }

        log.atError()
            .setCause(failure)
            .setMessage("Capture is compromised or the proxy is unstable; terminating immediately")
            .log();
        System.err.println(
            "Capture is compromised or the proxy is unstable; terminating with exit code " + exitCode
        );

        var terminationThread = new Thread(
            () -> flushLogsAndHalt(failure),
            "capture-failure-terminator"
        );
        terminationThread.setDaemon(false);
        terminationThread.start();
    }

    @SuppressWarnings("java:S1181") // A log-backend Error must not prevent the required process halt.
    private void flushLogsAndHalt(Throwable failure) {
        var flushComplete = new CountDownLatch(1);
        var flushThread = new Thread(
            () -> {
                try {
                    flushLogs.run();
                } catch (Throwable flushFailure) {
                    System.err.println(
                        "Unable to flush logs after capture failure: " + flushFailure
                    );
                    failure.addSuppressed(flushFailure);
                } finally {
                    flushComplete.countDown();
                }
            },
            "capture-failure-log-flush"
        );
        flushThread.setDaemon(true);
        flushThread.start();
        try {
            if (!flushComplete.await(logFlushTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                System.err.println(
                    "Timed out flushing logs after capture failure; terminating now"
                );
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println(
                "Interrupted while flushing logs after capture failure; terminating now"
            );
        } finally {
            System.err.flush();
            haltProcess.accept(exitCode);
        }
    }

    private static Duration requirePositive(@NonNull Duration value) {
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("logFlushTimeout must be positive");
        }
        return value;
    }
}

package org.opensearch.migrations.trafficcapture.proxyserver;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

import lombok.extern.slf4j.Slf4j;

/**
 * Flushes capture-failure diagnostics and then terminates the process exactly once.
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
        Runnable flushLogs,
        IntConsumer haltProcess
    ) {
        this.exitCode = exitCode;
        this.logFlushTimeout = requirePositive(logFlushTimeout);
        this.flushLogs = Objects.requireNonNull(flushLogs);
        this.haltProcess = Objects.requireNonNull(haltProcess);
    }

    @Override
    public void accept(Throwable failure) {
        Objects.requireNonNull(failure);
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

    private static Duration requirePositive(Duration value) {
        Objects.requireNonNull(value);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("logFlushTimeout must be positive");
        }
        return value;
    }
}

package org.opensearch.migrations.replay;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ReplayProcessFatalHandlerTest {
    private static final int PROCESS_TIMEOUT_SECONDS = 15;

    @Test
    void emitsMetricFlushesDiagnosticsAndTerminatesExactlyOnce() {
        var events = new ArrayList<String>();
        var stderrBytes = new RecordingOutputStream(() -> {
            events.add("stderr-flush");
        });
        var errorStream = new PrintStream(stderrBytes, false, StandardCharsets.UTF_8);
        var handler = new ReplayProcessFatalHandler(
            ReplayProcessFatalHandler.Reason.EVENT_LOOP_TERMINATED,
            reason -> {
                Assertions.assertEquals(ReplayProcessFatalHandler.Reason.EVENT_LOOP_TERMINATED, reason);
                events.add("metric");
            },
            exitCode -> {
                Assertions.assertEquals(
                    ReplayProcessFatalHandler.Reason.EVENT_LOOP_TERMINATED.exitCode(),
                    exitCode
                );
                events.add("terminate");
            },
            () -> events.add("log4j-flush"),
            errorStream
        );
        var failure = new Error(
            "event-loop owner died",
            new IllegalStateException("root event-loop failure")
        );

        handler.onFatal(failure);
        handler.onFatal(new Error("duplicate fatal signal"));

        Assertions.assertEquals(
            List.of(
                "metric",
                "log4j-flush",
                "stderr-flush",
                "terminate"
            ),
            events
        );
        var stderr = stderrBytes.toString(StandardCharsets.UTF_8);
        Assertions.assertTrue(
            stderr.contains(ReplayProcessFatalHandler.Reason.EVENT_LOOP_TERMINATED.message())
        );
        Assertions.assertTrue(stderr.contains("java.lang.Error: event-loop owner died"));
        Assertions.assertTrue(stderr.contains("Caused by: java.lang.IllegalStateException: root event-loop failure"));
    }

    @Test
    void subprocessHaltsWithReasonSpecificExitCodeAfterWritingFatalDiagnostics() throws Exception {
        var process = launchFatalChild();
        var exited = process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
            Assertions.fail("fatal-handler subprocess did not terminate promptly");
        }
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        Assertions.assertEquals(
            ReplayProcessFatalHandler.Reason.EVENT_LOOP_TERMINATED.exitCode(),
            process.exitValue()
        );
        Assertions.assertTrue(
            output.contains(ReplayProcessFatalHandler.Reason.EVENT_LOOP_TERMINATED.message()),
            output
        );
        Assertions.assertTrue(output.contains("java.lang.Error: subprocess event-loop failure"), output);
        Assertions.assertTrue(
            output.contains("Caused by: java.lang.IllegalStateException: subprocess root cause"),
            output
        );
    }

    @Test
    void fatalHaltCodesAreDistinctFromNormalReplayerExitCodes() {
        Assertions.assertEquals(80, ReplayProcessFatalHandler.Reason.EVENT_LOOP_TERMINATED.exitCode());
        Assertions.assertEquals(89, ReplayProcessFatalHandler.Reason.UNEXPECTED_FATAL_ERROR.exitCode());
        for (var reason : ReplayProcessFatalHandler.Reason.values()) {
            Assertions.assertFalse(
                List.of(1, 2, 3, 4, 5).contains(reason.exitCode()),
                () -> reason + " must not reuse a normal System.exit code"
            );
        }
    }

    private static Process launchFatalChild() throws IOException {
        var javaExecutable = System.getProperty("java.home")
            + File.separator
            + "bin"
            + File.separator
            + "java";
        return new ProcessBuilder(
            javaExecutable,
            "-cp",
            System.getProperty("java.class.path"),
            FatalChild.class.getName()
        )
            .redirectErrorStream(true)
            .start();
    }

    public static final class FatalChild {
        private FatalChild() {}

        public static void main(String[] args) {
            var handler = new ReplayProcessFatalHandler(
                ReplayProcessFatalHandler.Reason.EVENT_LOOP_TERMINATED,
                ignored -> {},
                Runtime.getRuntime()::halt
            );
            handler.onFatal(
                new Error(
                    "subprocess event-loop failure",
                    new IllegalStateException("subprocess root cause")
                )
            );
            throw new AssertionError("Runtime.halt returned");
        }
    }

    private static final class RecordingOutputStream extends OutputStream {
        private final ByteArrayOutputStream delegate = new ByteArrayOutputStream();
        private final Runnable flushObserver;

        private RecordingOutputStream(Runnable flushObserver) {
            this.flushObserver = flushObserver;
        }

        @Override
        public void write(int value) {
            delegate.write(value);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
            delegate.write(bytes, offset, length);
        }

        @Override
        public void flush() {
            flushObserver.run();
        }

        private String toString(java.nio.charset.Charset charset) {
            return delegate.toString(charset);
        }
    }
}

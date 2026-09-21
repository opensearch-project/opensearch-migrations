/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.lifecycle;

import java.io.EOFException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.opensearch.migrations.replay.CapturedTrafficToHttpTransactionAccumulator;
import org.opensearch.migrations.replay.ReplayProcessFatalHandler;
import org.opensearch.migrations.replay.traffic.source.BufferedFlowController;
import org.opensearch.migrations.replay.traffic.source.ITrafficCaptureSource;
import org.opensearch.migrations.replay.traffic.source.SourceInput;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class ReplayIntakeOwnerTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    /** Proves R1: source inputs are applied in order on the dedicated replay-intake thread. */
    @Test
    void appliesWholeSourceBatchOnDedicatedOwnerThread() throws Exception {
        var fixture = new OwnerFixture();
        var source = Mockito.mock(ITrafficCaptureSource.class);
        var accumulator = Mockito.mock(CapturedTrafficToHttpTransactionAccumulator.class);
        var first = Mockito.mock(SourceInput.class);
        var second = Mockito.mock(SourceInput.class);
        var batch = new CompletableFuture<List<SourceInput>>();
        Mockito.when(source.readNextTrafficStreamChunk(Mockito.any()))
            .thenReturn(batch)
            .thenReturn(CompletableFuture.failedFuture(new EOFException("done")));
        var applied = new ArrayList<SourceInput>();
        var applyingThread = new AtomicReference<Thread>();
        Mockito.doAnswer(invocation -> {
            applyingThread.compareAndSet(null, Thread.currentThread());
            applied.add(invocation.getArgument(0));
            return null;
        }).when(accumulator).accept(Mockito.any());

        fixture.start();
        var reading = fixture.owner.startReading(source, accumulator, () -> null);
        batch.complete(List.of(first, second));
        reading.toCompletableFuture().get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);

        Assertions.assertEquals(List.of(first, second), applied);
        Assertions.assertEquals("replay-intake-owner", applyingThread.get().getName());
        Assertions.assertNotEquals(Thread.currentThread(), applyingThread.get());
        fixture.stop();
    }

    /** Proves R19: an unexpected replay-intake transition reaches the process-fatal handler. */
    @Test
    void injectedOwnerFailureReachesProcessFatalHandler() throws Exception {
        var exitCode = new CompletableFuture<Integer>();
        var fatalHandler = new ReplayProcessFatalHandler(
            ReplayProcessFatalHandler.Reason.UNEXPECTED_FATAL_ERROR,
            ignored -> {},
            exitCode::complete
        );
        var fixture = new OwnerFixture(fatalHandler::onFatal);
        var source = Mockito.mock(ITrafficCaptureSource.class);
        var accumulator = Mockito.mock(CapturedTrafficToHttpTransactionAccumulator.class);
        var input = Mockito.mock(SourceInput.class);
        Mockito.when(source.readNextTrafficStreamChunk(Mockito.any()))
            .thenReturn(CompletableFuture.completedFuture(List.of(input)));
        Mockito.doThrow(new IllegalStateException("injected intake failure"))
            .when(accumulator).accept(input);

        fixture.start();
        fixture.owner.startReading(source, accumulator, () -> null);

        Assertions.assertThrows(
            Exception.class,
            () -> fixture.owner.termination().toCompletableFuture()
                .get(TIMEOUT.toSeconds(), TimeUnit.SECONDS)
        );
        Assertions.assertEquals(
            ReplayProcessFatalHandler.Reason.UNEXPECTED_FATAL_ERROR.exitCode(),
            exitCode.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS)
        );
    }

    /** Proves required input rejection is fatal rather than silently dropped. */
    @Test
    void rejectedRequiredSubmissionReachesFatalHandler() throws Exception {
        var failures = new ArrayList<Error>();
        var fixture = new OwnerFixture(failures::add);
        fixture.start();
        fixture.stop();

        fixture.owner.submitRequired(
            new ReplayIntakeOwner.StopReading(new CompletableFuture<>())
        );

        Assertions.assertEquals(1, failures.size());
        Assertions.assertTrue(failures.get(0).getMessage().contains("StopReading"));
    }

    private static final class OwnerFixture {
        private final ReplayIntakeOwner owner;

        private OwnerFixture() {
            this(failure -> {
                throw failure;
            });
        }

        private OwnerFixture(ReplayIntakeOwner.FatalHandler fatalHandler) {
            owner = new ReplayIntakeOwner(fatalHandler);
            var permitPool = new AsyncPermitPool(
                1,
                owner::submitRequired,
                AsyncPermitPool.Metrics.NOOP
            );
            var progress = new ReplayProgressController(
                owner::submitRequired,
                new ReplayReadGate(Duration.ZERO, Mockito.mock(BufferedFlowController.class))
            );
            var ledger = new RecordDispositionLedger(owner::submitRequired);
            owner.configureOwnedComponents(permitPool, progress, ledger);
        }

        private void start() {
            owner.start();
        }

        private void stop() throws Exception {
            owner.stopOwner().toCompletableFuture().get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            owner.termination().toCompletableFuture().get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        }
    }
}

/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.lifecycle;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.ConnectionSessionKey;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.PartitionGenerationId;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.ReplayRequestId;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.SourceConnectionKey;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.PreparationOutcome;

import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class RequestReplayOwnerTest {
    private static final PartitionGenerationId PARTITION_GENERATION =
        new PartitionGenerationId(new TopicPartition("topic", 0), 1);
    private static final ReplayRequestId REQUEST_ID = new ReplayRequestId(
        new ConnectionSessionKey(new SourceConnectionKey("node", "connection"), 0, 1),
        0
    );

    @Test
    void tupleDurableCannotFinishBeforeRequestProcessingFinishedMilestone() {
        var owner = ownerWith(new TestPrepared(null, null));
        owner.registerProcessing(processingRegistration());
        owner.recordProcessingCompletion(
            new TargetConnectionOwner.RequestProcessingOutcome.TupleDurable()
        );

        var thrown = Assertions.assertThrows(IllegalStateException.class, owner::finishProcessing);

        Assertions.assertTrue(thrown.getMessage().contains("CompletionReceived"));
        Assertions.assertFalse(owner.processingMilestoneSubmitted());
    }

    @Test
    void tupleDurableFinishesOnlyAfterConnectionMilestoneAndProcessingMilestone() {
        var owner = ownerWith(new TestPrepared(null, null));
        owner.registerProcessing(processingRegistration());
        owner.markTurnActive();
        owner.recordProcessingCompletion(
            new TargetConnectionOwner.RequestProcessingOutcome.TupleDurable()
        );

        owner.markConnectionTurnFinished();
        Assertions.assertThrows(
            IllegalStateException.class,
            owner::markProcessingMilestoneSubmitted,
            "intake must accept ConnectionRequestFinished first"
        );
        owner.markConnectionTurnAccepted();
        owner.markProcessingMilestoneSubmitted();
        Assertions.assertDoesNotThrow(owner::finishProcessing);

        Assertions.assertTrue(owner.processingMilestoneSubmitted());
    }

    @Test
    void cancelledCompletionMayFinishWithoutNormalProcessingMilestone() {
        var owner = ownerWith(new TestPrepared(null, null));
        var cause = new CancellationException("cancelled");
        owner.registerProcessing(processingRegistration());
        owner.markTurnActive();
        owner.cancelConnectionTurn(cause);
        owner.cancelProcessing(cause);
        owner.beginProcessingCancellationAcknowledgement();
        owner.acceptProcessingCancellationAcknowledgement(
            new ReplayOutcomes.ProcessingCancellationResult.CancellationWon()
        );
        owner.recordProcessingCompletion(
            new TargetConnectionOwner.RequestProcessingOutcome.RequestCleanupFinished(cause)
        );

        Assertions.assertFalse(owner.processingMilestoneSubmitted());
        Assertions.assertDoesNotThrow(owner::finishProcessing);
    }

    @Test
    void locallyCancelledUnregisteredProcessingMayFinishWithoutMilestone() {
        var owner = ownerWith(new TestPrepared(null, null));
        var cause = new CancellationException("cancelled before registration");
        owner.cancelConnectionTurn(cause);
        owner.cancelProcessing(cause);

        Assertions.assertFalse(owner.processingMilestoneSubmitted());
        Assertions.assertDoesNotThrow(owner::finishProcessing);
    }

    @Test
    void queuedRequestCannotEmitConnectionTurnFinished() {
        var owner = ownerWith(new TestPrepared(null, null));

        var thrown = Assertions.assertThrows(
            IllegalStateException.class,
            owner::markConnectionTurnFinished
        );

        Assertions.assertTrue(thrown.getMessage().contains("QUEUED"));
        Assertions.assertFalse(owner.connectionTurnSettled());
    }

    @Test
    void tupleDurableCannotArriveBeforeCancellationDecision() {
        var owner = ownerWith(new TestPrepared(null, null));
        owner.registerProcessing(processingRegistration());
        owner.cancelProcessing(new CancellationException("cancelled"));

        var thrown = Assertions.assertThrows(
            IllegalStateException.class,
            () -> owner.recordProcessingCompletion(
                new TargetConnectionOwner.RequestProcessingOutcome.TupleDurable()
            )
        );

        Assertions.assertTrue(thrown.getMessage().contains("CancellationRequested"));
    }

    @Test
    void tupleDurableIsAcceptedWhenProcessingCompletionWinsCancellationRace() {
        var owner = ownerWith(new TestPrepared(null, null));
        owner.registerProcessing(processingRegistration());
        owner.markTurnActive();
        owner.markConnectionTurnFinished();
        owner.markConnectionTurnAccepted();
        owner.cancelProcessing(new CancellationException("cancelled"));
        owner.beginProcessingCancellationAcknowledgement();
        owner.acceptProcessingCancellationAcknowledgement(
            new ReplayOutcomes.ProcessingCancellationResult.ProcessingCompletionWon()
        );

        Assertions.assertDoesNotThrow(
            () -> owner.recordProcessingCompletion(
                new TargetConnectionOwner.RequestProcessingOutcome.TupleDurable()
            )
        );
        Assertions.assertInstanceOf(
            TargetConnectionOwner.RequestProcessingOutcome.TupleDurable.class,
            owner.processingOutcome()
        );
        Assertions.assertDoesNotThrow(owner::markProcessingMilestoneSubmitted);
        Assertions.assertDoesNotThrow(owner::finishProcessing);
    }

    @Test
    void cancelledCompletionRequiresAnExplicitCancellationRequest() {
        var owner = ownerWith(new TestPrepared(null, null));
        owner.registerProcessing(processingRegistration());

        var thrown = Assertions.assertThrows(
            IllegalStateException.class,
            () -> owner.recordProcessingCompletion(
                new TargetConnectionOwner.RequestProcessingOutcome.RequestCleanupFinished(
                    new CancellationException("unexpected")
                )
            )
        );

        Assertions.assertTrue(thrown.getMessage().contains("Registered"));
    }

    @Test
    void pendingPreparationCancellationBlocksCleanupCompletion() {
        var owner = ownerWith(new TestPrepared(null, null));
        var cause = new CancellationException("cancelled");
        owner.cancelConnectionTurn(cause);
        owner.cancelProcessing(cause);
        Assertions.assertTrue(owner.beginPreparationCancellation());

        Assertions.assertThrows(IllegalStateException.class, owner::finishProcessing);
        owner.acceptPreparationCancellationAcknowledgement();
        Assertions.assertDoesNotThrow(owner::finishProcessing);
    }

    @Test
    void unfinishedProcessingStatesCannotFinish() {
        var unregistered = ownerWith(new TestPrepared(null, null));
        var unregisteredFailure = Assertions.assertThrows(
            IllegalStateException.class,
            unregistered::finishProcessing
        );
        Assertions.assertTrue(unregisteredFailure.getMessage().contains("Unregistered"));

        var registered = ownerWith(new TestPrepared(null, null));
        registered.registerProcessing(processingRegistration());
        var registeredFailure = Assertions.assertThrows(
            IllegalStateException.class,
            registered::finishProcessing
        );
        Assertions.assertTrue(registeredFailure.getMessage().contains("Registered"));

        var cancellationPending = ownerWith(new TestPrepared(null, null));
        cancellationPending.registerProcessing(processingRegistration());
        cancellationPending.cancelProcessing(new CancellationException("pending"));
        var cancellationFailure = Assertions.assertThrows(
            IllegalStateException.class,
            cancellationPending::finishProcessing
        );
        Assertions.assertTrue(
            cancellationFailure.getMessage().contains("CancellationRequested")
        );

        var failed = ownerWith(new TestPrepared(null, null));
        failed.markProcessingFailed(new IllegalStateException("failed"));
        var processingFailure = Assertions.assertThrows(
            IllegalStateException.class,
            failed::finishProcessing
        );
        Assertions.assertTrue(processingFailure.getMessage().contains("Failed"));
    }

    @Test
    void connectionAndProcessingMilestonesAreOneShot() {
        var owner = ownerWith(new TestPrepared(null, null));
        owner.registerProcessing(processingRegistration());
        owner.markTurnActive();
        owner.markConnectionTurnFinished();

        Assertions.assertThrows(IllegalStateException.class, owner::markConnectionTurnFinished);
        owner.markConnectionTurnAccepted();
        Assertions.assertThrows(IllegalStateException.class, owner::markConnectionTurnAccepted);

        owner.recordProcessingCompletion(
            new TargetConnectionOwner.RequestProcessingOutcome.TupleDurable()
        );
        owner.markProcessingMilestoneSubmitted();
        Assertions.assertThrows(
            IllegalStateException.class,
            owner::markProcessingMilestoneSubmitted
        );
        owner.finishProcessing();
        Assertions.assertThrows(IllegalStateException.class, owner::finishProcessing);
    }

    @Test
    void processingCompletionAndFirstTargetWriteAreOneShot() {
        var owner = ownerWith(new TestPrepared(null, null));
        var cause = new CancellationException("cancelled");
        owner.registerProcessing(processingRegistration());
        owner.markTurnActive();
        owner.markFirstTargetWriteSubmitted();
        Assertions.assertTrue(owner.firstTargetWriteSubmitted());
        Assertions.assertThrows(
            IllegalStateException.class,
            owner::markFirstTargetWriteSubmitted
        );
        owner.cancelConnectionTurn(cause);
        owner.cancelProcessing(cause);
        owner.beginProcessingCancellationAcknowledgement();
        owner.acceptProcessingCancellationAcknowledgement(
            new ReplayOutcomes.ProcessingCancellationResult.CancellationWon()
        );
        owner.recordProcessingCompletion(
            new TargetConnectionOwner.RequestProcessingOutcome.RequestCleanupFinished(cause)
        );

        Assertions.assertThrows(
            IllegalStateException.class,
            () -> owner.recordProcessingCompletion(
                new TargetConnectionOwner.RequestProcessingOutcome.RequestCleanupFinished(cause)
            )
        );
    }

    @Test
    void mutableStateRejectsAccessFromASecondThread() throws InterruptedException {
        var owner = ownerWith(new TestPrepared(null, null));
        var failure = new AtomicReference<Throwable>();
        var nonOwner = new Thread(
            () -> {
                try {
                    owner.markTurnActive();
                } catch (Throwable t) {
                    failure.set(t);
                }
            },
            "request-owner-violator"
        );

        nonOwner.start();
        nonOwner.join();

        Assertions.assertInstanceOf(IllegalStateException.class, failure.get());
        Assertions.assertTrue(failure.get().getMessage().contains("non-owner thread"));
        Assertions.assertDoesNotThrow(owner::markTurnActive);
    }

    @Test
    void nonOwnerCallerCannotClaimOwnershipBeforeConfiguredOwner() throws InterruptedException {
        var prepared = new TestPrepared(null, null);
        var owner = newOwner(prepared);
        var failure = new AtomicReference<Throwable>();
        var nonOwner = new Thread(
            () -> {
                try {
                    owner.recordPreparation(new PreparationOutcome.Prepared<>(prepared));
                } catch (Throwable t) {
                    failure.set(t);
                }
            },
            "request-owner-first-violator"
        );

        nonOwner.start();
        nonOwner.join();

        Assertions.assertInstanceOf(IllegalStateException.class, failure.get());
        Assertions.assertTrue(failure.get().getMessage().contains("non-owner thread"));
        Assertions.assertDoesNotThrow(
            () -> owner.recordPreparation(new PreparationOutcome.Prepared<>(prepared))
        );
    }

    @Test
    void ownerThreadCallOutsideMailboxTaskIsRejected() {
        var prepared = new TestPrepared(null, null);
        var inMailbox = new AtomicBoolean();
        var owner = new RequestReplayOwner<TestPrepared, String>(
            PARTITION_GENERATION,
            REQUEST_ID,
            new TargetConnectionOwner.RequestPreparation<>() {
                @Override
                public CompletionStage<PreparationOutcome<TestPrepared>> completion() {
                    return CompletableFuture.completedFuture(
                        new PreparationOutcome.Prepared<>(prepared)
                    );
                }

                @Override
                public CompletionStage<Void> cancel(CancellationException cause) {
                    return CompletableFuture.completedFuture(null);
                }
            },
            inMailbox::get
        );

        var failure = Assertions.assertThrows(
            IllegalStateException.class,
            () -> owner.recordPreparation(new PreparationOutcome.Prepared<>(prepared))
        );
        Assertions.assertTrue(failure.getMessage().contains("non-owner thread"));

        inMailbox.set(true);
        Assertions.assertDoesNotThrow(
            () -> owner.recordPreparation(new PreparationOutcome.Prepared<>(prepared))
        );
    }

    @Test
    void releasePreparedClosesAfterConnectionTurnReleaseFailsAndIsOneShot() {
        var connectionFailure = new Exception("connection turn release failed");
        var closeFailure = new Exception("prepared close failed");
        var prepared = new TestPrepared(connectionFailure, closeFailure);
        var owner = ownerWith(prepared);

        var thrown = Assertions.assertThrows(Exception.class, owner::releasePrepared);

        Assertions.assertSame(connectionFailure, thrown);
        Assertions.assertArrayEquals(new Throwable[] {closeFailure}, thrown.getSuppressed());
        Assertions.assertEquals(1, prepared.connectionTurnFinishedCount);
        Assertions.assertEquals(1, prepared.closeCount);

        Assertions.assertDoesNotThrow(owner::releasePrepared);
        Assertions.assertEquals(1, prepared.connectionTurnFinishedCount);
        Assertions.assertEquals(1, prepared.closeCount);
    }

    @Test
    void releasePreparedPreservesCloseErrorWhenConnectionTurnReleaseThrowsException() {
        var connectionFailure = new Exception("connection turn release failed");
        var closeFailure = new AssertionError("prepared close failed");
        var prepared = new TestPrepared(connectionFailure, closeFailure);
        var owner = ownerWith(prepared);

        var thrown = Assertions.assertThrows(AssertionError.class, owner::releasePrepared);

        Assertions.assertSame(closeFailure, thrown);
        Assertions.assertArrayEquals(new Throwable[] {connectionFailure}, thrown.getSuppressed());
        Assertions.assertEquals(1, prepared.connectionTurnFinishedCount);
        Assertions.assertEquals(1, prepared.closeCount);
    }

    @Test
    void releasePreparedPreservesConnectionTurnErrorAndSuppressesCloseFailure() {
        var connectionFailure = new AssertionError("connection turn release failed");
        var closeFailure = new Exception("prepared close failed");
        var prepared = new TestPrepared(connectionFailure, closeFailure);
        var owner = ownerWith(prepared);

        var thrown = Assertions.assertThrows(AssertionError.class, owner::releasePrepared);

        Assertions.assertSame(connectionFailure, thrown);
        Assertions.assertArrayEquals(new Throwable[] {closeFailure}, thrown.getSuppressed());
        Assertions.assertEquals(1, prepared.connectionTurnFinishedCount);
        Assertions.assertEquals(1, prepared.closeCount);
    }

    private static RequestReplayOwner<TestPrepared, String> ownerWith(TestPrepared prepared) {
        var owner = newOwner(prepared);
        owner.recordPreparation(new PreparationOutcome.Prepared<>(prepared));
        return owner;
    }

    private static RequestReplayOwner<TestPrepared, String> newOwner(TestPrepared prepared) {
        var ownerThread = Thread.currentThread();
        return new RequestReplayOwner<TestPrepared, String>(
            PARTITION_GENERATION,
            REQUEST_ID,
            new TargetConnectionOwner.RequestPreparation<>() {
                @Override
                public CompletionStage<PreparationOutcome<TestPrepared>> completion() {
                    return CompletableFuture.completedFuture(new PreparationOutcome.Prepared<>(prepared));
                }

                @Override
                public CompletionStage<Void> cancel(CancellationException cause) {
                    return CompletableFuture.completedFuture(null);
                }
            },
            () -> Thread.currentThread() == ownerThread
        );
    }

    private static TargetConnectionOwner.RequestProcessingRegistration processingRegistration() {
        return new TargetConnectionOwner.RequestProcessingRegistration(
            new CompletableFuture<>(),
            ignored -> CompletableFuture.completedFuture(
                new ReplayOutcomes.ProcessingCancellationResult.CancellationWon()
            )
        );
    }

    private static final class TestPrepared implements TargetConnectionOwner.PreparedRequest {
        private final Throwable connectionTurnFailure;
        private final Throwable closeFailure;
        private int connectionTurnFinishedCount;
        private int closeCount;

        private TestPrepared(Throwable connectionTurnFailure, Throwable closeFailure) {
            this.connectionTurnFailure = connectionTurnFailure;
            this.closeFailure = closeFailure;
        }

        @Override
        public void connectionTurnFinished() throws Exception {
            connectionTurnFinishedCount++;
            throwFailure(connectionTurnFailure);
        }

        @Override
        public void close() throws Exception {
            closeCount++;
            throwFailure(closeFailure);
        }

        private static void throwFailure(Throwable failure) throws Exception {
            if (failure instanceof Error error) {
                throw error;
            }
            if (failure instanceof Exception exception) {
                throw exception;
            }
        }
    }
}

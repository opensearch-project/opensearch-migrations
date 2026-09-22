package org.opensearch.migrations.replay.lifecycle;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.PartitionGenerationId;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.PreparationOutcome;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.ProcessingCancellationResult;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.SessionOutcome;
import org.opensearch.migrations.replay.lifecycle.TargetConnectionOwner.RequestTurnResult;
import org.opensearch.migrations.replay.lifecycle.TargetConnectionOwnerTestSupport.TestExchange;
import org.opensearch.migrations.replay.lifecycle.TargetConnectionOwnerTestSupport.TestPrepared;
import org.opensearch.migrations.replay.testing.TestEventLoop;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.opensearch.migrations.replay.lifecycle.TargetConnectionOwnerTestSupport.PARTITION_GENERATION;
import static org.opensearch.migrations.replay.lifecycle.TargetConnectionOwnerTestSupport.acceptingLifecycleSink;
import static org.opensearch.migrations.replay.lifecycle.TargetConnectionOwnerTestSupport.admit;
import static org.opensearch.migrations.replay.lifecycle.TargetConnectionOwnerTestSupport.owner;
import static org.opensearch.migrations.replay.lifecycle.TargetConnectionOwnerTestSupport.processing;
import static org.opensearch.migrations.replay.lifecycle.TargetConnectionOwnerTestSupport.request;

class TargetConnectionOwnerAdmissionTest {
    @Test
    void preparationMayFinishOutOfOrderButExecutionRemainsInCapturedOrder() {
        var eventLoop = new TestEventLoop();
        var exchange = new TestExchange();
        var fatalFailures = new ArrayList<Error>();
        var owner = owner(
            eventLoop,
            exchange,
            fatalFailures,
            acceptingLifecycleSink()
        );
        var firstPreparation =
            new CompletableFuture<PreparationOutcome<TestPrepared>>();
        var secondPreparation =
            new CompletableFuture<PreparationOutcome<TestPrepared>>();
        var firstProcessing =
            new CompletableFuture<TargetConnectionOwner.RequestProcessingOutcome>();
        var secondProcessing =
            new CompletableFuture<TargetConnectionOwner.RequestProcessingOutcome>();

        var first = admit(
            owner,
            request(0),
            0,
            firstPreparation,
            processing(firstProcessing)
        );
        var second = admit(
            owner,
            request(1),
            1,
            secondPreparation,
            processing(secondProcessing)
        );
        secondPreparation.complete(
            new PreparationOutcome.Prepared<>(new TestPrepared("second"))
        );
        eventLoop.runUntilIdle();

        Assertions.assertInstanceOf(
            TargetConnectionOwner.RequestAdmissionAccepted.class,
            first.admissionResult().toCompletableFuture().join()
        );
        Assertions.assertInstanceOf(
            TargetConnectionOwner.RequestAdmissionAccepted.class,
            second.admissionResult().toCompletableFuture().join()
        );
        Assertions.assertTrue(exchange.executed.isEmpty());

        firstPreparation.complete(
            new PreparationOutcome.Prepared<>(new TestPrepared("first"))
        );
        eventLoop.runUntilIdle();
        Assertions.assertEquals(List.of("first"), exchange.executed);

        exchange.completeNext(new RequestTurnResult.Completed<>("first-response"));
        eventLoop.runUntilIdle();
        Assertions.assertEquals(List.of("first", "second"), exchange.executed);
        Assertions.assertTrue(fatalFailures.isEmpty());
    }

    @Test
    void nonzeroCapturedOrdinalsPreserveRequestAndCloseOrder() {
        var eventLoop = new TestEventLoop();
        var exchange = new TestExchange();
        var fatalFailures = new ArrayList<Error>();
        var owner = owner(
            eventLoop,
            exchange,
            fatalFailures,
            acceptingLifecycleSink()
        );
        var firstPreparation =
            new CompletableFuture<PreparationOutcome<TestPrepared>>();
        var secondPreparation =
            new CompletableFuture<PreparationOutcome<TestPrepared>>();
        var firstProcessing =
            new CompletableFuture<TargetConnectionOwner.RequestProcessingOutcome>();
        var secondProcessing =
            new CompletableFuture<TargetConnectionOwner.RequestProcessingOutcome>();
        var first = owner.admitRequestWithAcceptance(
            PARTITION_GENERATION,
            request(0),
            7,
            Instant.EPOCH,
            Instant.EPOCH,
            TargetConnectionOwnerTestSupport.preparation(firstPreparation),
            processing(firstProcessing)
        );
        var second = owner.admitRequestWithAcceptance(
            PARTITION_GENERATION,
            request(1),
            8,
            Instant.EPOCH,
            Instant.EPOCH,
            TargetConnectionOwnerTestSupport.preparation(secondPreparation),
            processing(secondProcessing)
        );
        var close = owner.admitCloseWithAcceptance(
            PARTITION_GENERATION,
            9,
            Instant.EPOCH
        );

        secondPreparation.complete(
            new PreparationOutcome.Prepared<>(new TestPrepared("second"))
        );
        firstPreparation.complete(
            new PreparationOutcome.Prepared<>(new TestPrepared("first"))
        );
        eventLoop.runUntilIdle();
        Assertions.assertEquals(List.of("first"), exchange.executed);

        exchange.completeNext(new RequestTurnResult.Completed<>("first-response"));
        firstProcessing.complete(
            new TargetConnectionOwner.RequestProcessingOutcome.TupleDurable()
        );
        eventLoop.runUntilIdle();
        Assertions.assertEquals(List.of("first", "second"), exchange.executed);

        exchange.completeNext(new RequestTurnResult.Completed<>("second-response"));
        secondProcessing.complete(
            new TargetConnectionOwner.RequestProcessingOutcome.TupleDurable()
        );
        eventLoop.runUntilIdle();

        Assertions.assertInstanceOf(
            TargetConnectionOwner.RequestAdmissionAccepted.class,
            first.admissionResult().toCompletableFuture().join()
        );
        Assertions.assertInstanceOf(
            TargetConnectionOwner.RequestAdmissionAccepted.class,
            second.admissionResult().toCompletableFuture().join()
        );
        Assertions.assertTrue(close.admissionAccepted().toCompletableFuture().isDone());
        Assertions.assertEquals(1, exchange.closeCalls);
        Assertions.assertInstanceOf(
            SessionOutcome.Closed.class,
            close.closeCompletion().toCompletableFuture().join()
        );
        Assertions.assertTrue(fatalFailures.isEmpty());
    }

    @Test
    void regressingCapturedOrdinalIsProcessFatal() {
        var eventLoop = new TestEventLoop();
        var fatalFailures = new ArrayList<Error>();
        var owner = owner(
            eventLoop,
            new TestExchange(),
            fatalFailures,
            acceptingLifecycleSink()
        );
        var firstProcessing =
            new CompletableFuture<TargetConnectionOwner.RequestProcessingOutcome>();
        var rejectedPrepared = new TestPrepared("regressing");
        var preparationCancellations = new AtomicInteger();
        var processingCancellations = new AtomicInteger();
        var rejectedProcessingCompletion =
            new CompletableFuture<TargetConnectionOwner.RequestProcessingOutcome>();
        var rejectedPreparation =
            new TargetConnectionOwner.RequestPreparation<TestPrepared>() {
                @Override
                public CompletionStage<PreparationOutcome<TestPrepared>> completion() {
                    return CompletableFuture.completedFuture(
                        new PreparationOutcome.Prepared<>(rejectedPrepared)
                    );
                }

                @Override
                public CompletionStage<Void> cancel(CancellationException cause) {
                    preparationCancellations.incrementAndGet();
                    rejectedPrepared.close();
                    return CompletableFuture.completedFuture(null);
                }
            };
        var rejectedProcessing = TargetConnectionOwner.RequestProcessingRegistration
            .withTypedCancellation(
                rejectedProcessingCompletion,
                cause -> {
                    processingCancellations.incrementAndGet();
                    rejectedProcessingCompletion.complete(
                        new TargetConnectionOwner.RequestProcessingOutcome
                            .RequestCleanupFinished(cause)
                    );
                    return CompletableFuture.completedFuture(
                        new ProcessingCancellationResult.CancellationWon()
                    );
                }
            );
        owner.admitRequestWithAcceptance(
            PARTITION_GENERATION,
            request(0),
            7,
            Instant.EPOCH,
            Instant.EPOCH,
            TargetConnectionOwnerTestSupport.preparation(new CompletableFuture<>()),
            processing(firstProcessing)
        );
        var regressing = owner.admitRequestWithAcceptance(
            PARTITION_GENERATION,
            request(1),
            7,
            Instant.EPOCH,
            Instant.EPOCH,
            rejectedPreparation,
            rejectedProcessing
        );
        eventLoop.runUntilIdle();

        Assertions.assertEquals(1, fatalFailures.size());
        Assertions.assertTrue(
            fatalFailures.get(0).getCause().getMessage().contains(
                "captured ordinal 7 did not follow 7"
            )
        );
        var rejected = Assertions.assertInstanceOf(
            TargetConnectionOwner.RequestAdmissionRejected.class,
            regressing.admissionResult().toCompletableFuture().join()
        );
        Assertions.assertEquals(0, preparationCancellations.get());
        Assertions.assertEquals(0, processingCancellations.get());
        Assertions.assertEquals(0, rejectedPrepared.closeCount);

        rejectedPreparation.cancel(rejected.cause()).toCompletableFuture().join();
        rejectedProcessing.rejectAdmission(rejected.cause()).toCompletableFuture().join();

        Assertions.assertEquals(1, preparationCancellations.get());
        Assertions.assertEquals(1, processingCancellations.get());
        Assertions.assertEquals(1, rejectedPrepared.closeCount);
    }

    @Test
    void wrongGenerationIsRejectedWithoutTakingCleanupOwnership() {
        var eventLoop = new TestEventLoop();
        var fatalFailures = new ArrayList<Error>();
        var owner = owner(
            eventLoop,
            new TestExchange(),
            fatalFailures,
            acceptingLifecycleSink()
        );
        var prepared = new TestPrepared("wrong-generation");
        var preparationCancellations = new AtomicInteger();
        var processingCancellations = new AtomicInteger();
        var processingCompletion =
            new CompletableFuture<TargetConnectionOwner.RequestProcessingOutcome>();
        var preparation = new TargetConnectionOwner.RequestPreparation<TestPrepared>() {
            @Override
            public CompletionStage<PreparationOutcome<TestPrepared>> completion() {
                return CompletableFuture.completedFuture(
                    new PreparationOutcome.Prepared<>(prepared)
                );
            }

            @Override
            public CompletionStage<Void> cancel(CancellationException cause) {
                preparationCancellations.incrementAndGet();
                prepared.close();
                return CompletableFuture.completedFuture(null);
            }
        };
        var processing = TargetConnectionOwner.RequestProcessingRegistration
            .withTypedCancellation(
                processingCompletion,
                cause -> {
                    processingCancellations.incrementAndGet();
                    processingCompletion.complete(
                        new TargetConnectionOwner.RequestProcessingOutcome
                            .RequestCleanupFinished(cause)
                    );
                    return CompletableFuture.completedFuture(
                        new ProcessingCancellationResult.CancellationWon()
                    );
                }
            );
        var wrongGeneration = new PartitionGenerationId(
            PARTITION_GENERATION.topicPartition(),
            PARTITION_GENERATION.localSequence() + 1
        );

        var admission = owner.admitRequestWithAcceptance(
            wrongGeneration,
            request(0),
            0,
            Instant.EPOCH,
            Instant.EPOCH,
            preparation,
            processing
        );
        eventLoop.runUntilIdle();

        var rejected = Assertions.assertInstanceOf(
            TargetConnectionOwner.RequestAdmissionRejected.class,
            admission.admissionResult().toCompletableFuture().join()
        );
        Assertions.assertEquals(0, preparationCancellations.get());
        Assertions.assertEquals(0, processingCancellations.get());
        Assertions.assertEquals(0, prepared.closeCount);
        Assertions.assertTrue(fatalFailures.isEmpty());

        preparation.cancel(rejected.cause()).toCompletableFuture().join();
        processing.rejectAdmission(rejected.cause()).toCompletableFuture().join();

        Assertions.assertEquals(1, preparationCancellations.get());
        Assertions.assertEquals(1, processingCancellations.get());
        Assertions.assertEquals(1, prepared.closeCount);
    }

    @Test
    void rejectedMailboxSubmissionReturnsCleanupOwnershipToSender() {
        var eventLoop = new TestEventLoop();
        var fatalFailures = new ArrayList<Error>();
        var owner = owner(
            eventLoop,
            new TestExchange(),
            fatalFailures,
            acceptingLifecycleSink()
        );
        var prepared = new TestPrepared("rejected");
        var preparationCancellations = new AtomicInteger();
        var processingCancellations = new AtomicInteger();
        var processingCompletion =
            new CompletableFuture<TargetConnectionOwner.RequestProcessingOutcome>();
        var preparation = new TargetConnectionOwner.RequestPreparation<TestPrepared>() {
            @Override
            public CompletionStage<PreparationOutcome<TestPrepared>> completion() {
                return CompletableFuture.completedFuture(
                    new PreparationOutcome.Prepared<>(prepared)
                );
            }

            @Override
            public CompletionStage<Void> cancel(CancellationException cause) {
                preparationCancellations.incrementAndGet();
                prepared.close();
                return CompletableFuture.completedFuture(null);
            }
        };
        var processing = TargetConnectionOwner.RequestProcessingRegistration
            .withTypedCancellation(
                processingCompletion,
                cause -> {
                    processingCancellations.incrementAndGet();
                    processingCompletion.complete(
                        new TargetConnectionOwner.RequestProcessingOutcome
                            .RequestCleanupFinished(cause)
                    );
                    return CompletableFuture.completedFuture(
                        new ProcessingCancellationResult.CancellationWon()
                    );
                }
            );
        eventLoop.rejectNewTasks();

        var admission = owner.admitRequestWithAcceptance(
            PARTITION_GENERATION,
            request(0),
            0,
            Instant.EPOCH,
            Instant.EPOCH,
            preparation,
            processing
        );

        var rejected = Assertions.assertInstanceOf(
            TargetConnectionOwner.RequestAdmissionRejected.class,
            admission.admissionResult().toCompletableFuture().join()
        );
        Assertions.assertInstanceOf(
            RequestTurnResult.Cancelled.class,
            admission.turnCompletion().toCompletableFuture().join()
        );
        Assertions.assertEquals(0, preparationCancellations.get());
        Assertions.assertEquals(0, processingCancellations.get());
        Assertions.assertEquals(0, prepared.closeCount);
        Assertions.assertEquals(1, fatalFailures.size());

        preparation.cancel(rejected.cause()).toCompletableFuture().join();
        processing.rejectAdmission(rejected.cause()).toCompletableFuture().join();

        Assertions.assertEquals(1, preparationCancellations.get());
        Assertions.assertEquals(1, processingCancellations.get());
        Assertions.assertEquals(1, prepared.closeCount);
    }

    @Test
    void acceptedAdmissionRemainsOwnerOwnedWhenPreparationBeginThrows() {
        var eventLoop = new TestEventLoop();
        var fatalFailures = new ArrayList<Error>();
        var beginFailure = new IllegalStateException("begin failed");
        var processingCompletion =
            new CompletableFuture<TargetConnectionOwner.RequestProcessingOutcome>();
        var processing = processing(processingCompletion);
        var preparation = new TargetConnectionOwner.RequestPreparation<TestPrepared>() {
            @Override
            public CompletionStage<PreparationOutcome<TestPrepared>> completion() {
                return new CompletableFuture<>();
            }

            @Override
            public void begin() {
                throw beginFailure;
            }

            @Override
            public CompletionStage<Void> cancel(CancellationException cause) {
                return CompletableFuture.completedFuture(null);
            }
        };
        var owner = owner(
            eventLoop,
            new TestExchange(),
            fatalFailures,
            acceptingLifecycleSink()
        );

        var admission = owner.admitRequestWithAcceptance(
            PARTITION_GENERATION,
            request(0),
            0,
            Instant.EPOCH,
            Instant.EPOCH,
            preparation,
            processing
        );
        eventLoop.runUntilIdle();

        Assertions.assertInstanceOf(
            TargetConnectionOwner.RequestAdmissionAccepted.class,
            admission.admissionResult().toCompletableFuture().join()
        );
        Assertions.assertEquals(1, fatalFailures.size());
        Assertions.assertSame(beginFailure, fatalFailures.get(0).getCause());
        Assertions.assertSame(
            beginFailure,
            Assertions.assertThrows(
                java.util.concurrent.CompletionException.class,
                () -> admission.turnCompletion().toCompletableFuture().join()
            ).getCause()
        );
        Assertions.assertSame(
            beginFailure,
            Assertions.assertThrows(
                java.util.concurrent.CompletionException.class,
                () -> processing.lifecycleHandled().toCompletableFuture().join()
            ).getCause()
        );
    }

    @Test
    void admissionAfterCapturedCloseReturnsCleanupOwnershipToSender() {
        var eventLoop = new TestEventLoop();
        var exchange = new TestExchange();
        var fatalFailures = new ArrayList<Error>();
        var owner = owner(
            eventLoop,
            exchange,
            fatalFailures,
            acceptingLifecycleSink()
        );
        owner.admitCloseWithAcceptance(PARTITION_GENERATION, 0, Instant.EPOCH);
        eventLoop.runUntilIdle();
        var preparationCancellations = new AtomicInteger();
        var processingCancellations = new AtomicInteger();
        var processingCompletion =
            new CompletableFuture<TargetConnectionOwner.RequestProcessingOutcome>();
        var preparation = new TargetConnectionOwner.RequestPreparation<TestPrepared>() {
            @Override
            public CompletionStage<PreparationOutcome<TestPrepared>> completion() {
                return new CompletableFuture<>();
            }

            @Override
            public CompletionStage<Void> cancel(CancellationException cause) {
                preparationCancellations.incrementAndGet();
                return CompletableFuture.completedFuture(null);
            }
        };
        var processing = TargetConnectionOwner.RequestProcessingRegistration
            .withTypedCancellation(
                processingCompletion,
                cause -> {
                    processingCancellations.incrementAndGet();
                    processingCompletion.complete(
                        new TargetConnectionOwner.RequestProcessingOutcome
                            .RequestCleanupFinished(cause)
                    );
                    return CompletableFuture.completedFuture(
                        new ProcessingCancellationResult.CancellationWon()
                    );
                }
            );

        var admission = owner.admitRequestWithAcceptance(
            PARTITION_GENERATION,
            request(0),
            1,
            Instant.EPOCH,
            Instant.EPOCH,
            preparation,
            processing
        );
        eventLoop.runUntilIdle();

        var rejected = Assertions.assertInstanceOf(
            TargetConnectionOwner.RequestAdmissionRejected.class,
            admission.admissionResult().toCompletableFuture().join()
        );
        Assertions.assertEquals(0, preparationCancellations.get());
        Assertions.assertEquals(0, processingCancellations.get());
        Assertions.assertTrue(exchange.executed.isEmpty());

        preparation.cancel(rejected.cause()).toCompletableFuture().join();
        processing.rejectAdmission(rejected.cause()).toCompletableFuture().join();

        Assertions.assertEquals(1, preparationCancellations.get());
        Assertions.assertEquals(1, processingCancellations.get());
        Assertions.assertTrue(fatalFailures.isEmpty());
    }

    @Test
    void rejectedScheduledPreparationStartIsProcessFatal() {
        var eventLoop = new TestEventLoop();
        var exchange = new TestExchange();
        var fatalFailures = new ArrayList<Error>();
        var owner = owner(
            eventLoop,
            exchange,
            fatalFailures,
            acceptingLifecycleSink()
        );
        var processingCompletion =
            new CompletableFuture<TargetConnectionOwner.RequestProcessingOutcome>();
        owner.admitRequestWithAcceptance(
            PARTITION_GENERATION,
            request(0),
            0,
            Instant.EPOCH.plus(10, ChronoUnit.SECONDS),
            Instant.EPOCH.plus(10, ChronoUnit.SECONDS),
            TargetConnectionOwnerTestSupport.preparation(new CompletableFuture<>()),
            processing(processingCompletion)
        );
        eventLoop.rejectNewTasks();

        eventLoop.runUntilIdle();

        Assertions.assertEquals(1, fatalFailures.size());
        Assertions.assertTrue(
            fatalFailures.get(0).getMessage().contains(
                "scheduled preparation start"
            )
        );
        Assertions.assertTrue(exchange.executed.isEmpty());
    }

    @Test
    void rejectedOrderedCloseAdmissionIsProcessFatal() {
        var eventLoop = new TestEventLoop();
        var fatalFailures = new ArrayList<Error>();
        var owner = owner(
            eventLoop,
            new TestExchange(),
            fatalFailures,
            acceptingLifecycleSink()
        );
        eventLoop.rejectNewTasks();

        var close = owner.admitCloseWithAcceptance(
            PARTITION_GENERATION,
            0,
            Instant.EPOCH
        );

        Assertions.assertEquals(1, fatalFailures.size());
        Assertions.assertTrue(
            fatalFailures.get(0).getMessage().contains("ordered close admission")
        );
        Assertions.assertInstanceOf(
            RejectedExecutionException.class,
            fatalFailures.get(0).getCause()
        );
        var admissionFailure = Assertions.assertThrows(
            java.util.concurrent.CompletionException.class,
            () -> close.admissionAccepted().toCompletableFuture().join()
        );
        Assertions.assertSame(
            fatalFailures.get(0).getCause(),
            admissionFailure.getCause()
        );
        var failed = Assertions.assertInstanceOf(
            SessionOutcome.Failed.class,
            close.closeCompletion().toCompletableFuture().join()
        );
        Assertions.assertSame(admissionFailure.getCause(), failed.cause());
    }
}

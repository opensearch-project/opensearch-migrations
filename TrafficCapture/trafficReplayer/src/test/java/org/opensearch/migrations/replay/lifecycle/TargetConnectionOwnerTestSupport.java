package org.opensearch.migrations.replay.lifecycle;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.ConnectionSessionKey;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.PartitionGenerationId;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.ReplayRequestId;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.SourceConnectionKey;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.PreparationOutcome;
import org.opensearch.migrations.replay.lifecycle.TargetConnectionOwner.RequestTurnResult;
import org.opensearch.migrations.replay.testing.TestEventLoop;

import org.apache.kafka.common.TopicPartition;

final class TargetConnectionOwnerTestSupport {
    static final PartitionGenerationId PARTITION_GENERATION =
        new PartitionGenerationId(new TopicPartition("traffic", 2), 7);

    private TargetConnectionOwnerTestSupport() {}

    static ConnectionSessionKey session() {
        return new ConnectionSessionKey(
            new SourceConnectionKey("node", "connection"),
            0,
            1
        );
    }

    static ReplayRequestId request(int index) {
        return new ReplayRequestId(session(), index);
    }

    static TargetConnectionOwner<TestPrepared, String> owner(
        TestEventLoop eventLoop,
        TestExchange exchange,
        List<Error> fatalFailures,
        TargetConnectionOwner.RequestLifecycleSink lifecycleSink
    ) {
        return new TargetConnectionOwner<>(
            session(),
            PARTITION_GENERATION,
            eventLoop,
            exchange,
            TargetConnectionOwner.Metrics.NOOP,
            fatalFailures::add,
            lifecycleSink
        );
    }

    static TargetConnectionOwner.RequestAdmission<String> admit(
        TargetConnectionOwner<TestPrepared, String> owner,
        ReplayRequestId requestId,
        long capturedOrdinal,
        CompletionStage<PreparationOutcome<TestPrepared>> preparation,
        TargetConnectionOwner.RequestProcessingRegistration processing
    ) {
        return owner.admitRequestWithAcceptance(
            PARTITION_GENERATION,
            requestId,
            capturedOrdinal,
            Instant.EPOCH,
            Instant.EPOCH,
            preparation(preparation),
            processing
        );
    }

    static TargetConnectionOwner.RequestPreparation<TestPrepared> preparation(
        CompletionStage<PreparationOutcome<TestPrepared>> completion
    ) {
        return new TargetConnectionOwner.RequestPreparation<>() {
            @Override
            public CompletionStage<PreparationOutcome<TestPrepared>> completion() {
                return completion;
            }

            @Override
            public CompletionStage<Void> cancel(CancellationException cause) {
                return CompletableFuture.completedFuture(null);
            }
        };
    }

    static TargetConnectionOwner.RequestProcessingRegistration processing(
        CompletableFuture<TargetConnectionOwner.RequestProcessingOutcome> completion
    ) {
        return new TargetConnectionOwner.RequestProcessingRegistration(
            completion,
            cause -> {
                var cancellationWon = completion.complete(
                    new TargetConnectionOwner.RequestProcessingOutcome.RequestCleanupFinished(
                        cause
                    )
                );
                return CompletableFuture.completedFuture(
                    cancellationWon
                        ? new ReplayOutcomes.ProcessingCancellationResult.CancellationWon()
                        : new ReplayOutcomes.ProcessingCancellationResult.ProcessingCompletionWon()
                );
            }
        );
    }

    static TargetConnectionOwner.RequestLifecycleSink acceptingLifecycleSink() {
        return new TargetConnectionOwner.RequestLifecycleSink() {
            @Override
            public CompletionStage<Void> connectionRequestFinished(
                PartitionGenerationId partitionGenerationId,
                ReplayRequestId requestId
            ) {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletionStage<Void> requestProcessingFinished(
                PartitionGenerationId partitionGenerationId,
                ReplayRequestId requestId
            ) {
                return CompletableFuture.completedFuture(null);
            }
        };
    }

    static final class TestPrepared implements TargetConnectionOwner.PreparedRequest {
        final String name;
        int connectionTurnFinishedCount;
        int closeCount;

        TestPrepared(String name) {
            this.name = name;
        }

        @Override
        public void connectionTurnFinished() {
            connectionTurnFinishedCount++;
        }

        @Override
        public void close() {
            closeCount++;
        }
    }

    static final class TestExchange
        implements TargetConnectionOwner.TargetExchange<TestPrepared, String> {

        final List<String> executed = new ArrayList<>();
        final Queue<CompletableFuture<RequestTurnResult<String>>> active =
            new ArrayDeque<>();
        final CompletableFuture<Void> abortCompletion = new CompletableFuture<>();
        CompletableFuture<Void> closeCompletion = CompletableFuture.completedFuture(null);
        Consumer<ReplayRequestId> onExecute = ignored -> {};
        int closeCalls;

        @Override
        public CompletionStage<RequestTurnResult<String>> execute(
            ReplayRequestId requestId,
            TestPrepared preparedRequest
        ) {
            executed.add(preparedRequest.name);
            onExecute.accept(requestId);
            var completion = new CompletableFuture<RequestTurnResult<String>>();
            active.add(completion);
            return completion;
        }

        @Override
        public CompletionStage<Void> close() {
            closeCalls++;
            return closeCompletion;
        }

        @Override
        public CompletionStage<Void> abort(CancellationException cause) {
            return abortCompletion;
        }

        void completeNext(RequestTurnResult<String> result) {
            active.remove().complete(result);
        }
    }
}

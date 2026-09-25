/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.lifecycle;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

import org.opensearch.migrations.replay.identity.CapturedConnectionId;
import org.opensearch.migrations.replay.identity.ConnectionProcessingId;
import org.opensearch.migrations.replay.identity.KafkaRecordId;
import org.opensearch.migrations.replay.identity.PartitionGenerationId;
import org.opensearch.migrations.replay.identity.ReplayRequestId;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.RequestPreparationCancelled;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.RequestPreparationReady;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.RequestPreparationResult;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.RetryDecision;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.TargetAttemptOutcome;
import org.opensearch.migrations.replay.sink.TupleWriter;
import org.opensearch.migrations.replay.datatypes.HttpRequestTransformationStatus;
import org.opensearch.migrations.replay.testing.FakeClock;
import org.opensearch.migrations.replay.testing.TestEventLoop;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.replay.tracing.RootReplayerContext;

import io.opentelemetry.api.OpenTelemetry;
import org.apache.kafka.common.TopicPartition;

final class TargetConnectionOwnerTestSupport {
    static final PartitionGenerationId GENERATION =
        new PartitionGenerationId(new TopicPartition("traffic", 2), 7);
    static final ConnectionProcessingId CONNECTION = new ConnectionProcessingId(
        GENERATION,
        new CapturedConnectionId("node", "connection"),
        3
    );

    private TargetConnectionOwnerTestSupport() {}

    static ReplayRequestId request(long ordinal) {
        return new ReplayRequestId(CONNECTION, ordinal);
    }

    static final class Fixture {
        final FakeClock clock = new FakeClock();
        final TestEventLoop eventLoop = new TestEventLoop(clock);
        final RootReplayerContext replayContexts = new RootReplayerContext(OpenTelemetry.noop());
        final TestEventLoop tupleEventLoop;
        final List<Error> fatalFailures = new ArrayList<>();
        final AtomicInteger activePermits = new AtomicInteger();
        final FakePreparer preparer = new FakePreparer(clock);
        final FakeRetryPolicy retryPolicy = new FakeRetryPolicy();
        final FakeTargetChannel targetChannel = new FakeTargetChannel();
        final FakeTupleSink tupleSink = new FakeTupleSink();
        final List<RequestReplayOwner.RequestResult<String, TestPrepared, String, String>>
            tupleInputs = new ArrayList<>();
        final List<String> lifecycleEvents = new ArrayList<>();
        final List<String> transitionHistory = new ArrayList<>();
        final Map<String, CompletableFuture<Void>> lifecycleAcceptances =
            new LinkedHashMap<>();
        final TargetAttemptPermitProvider permitProvider;
        final TupleWriter<String> tupleWriter;
        final TargetConnectionOwner<String, TestPrepared, String, String, String> owner;

        Fixture() {
            this(1);
        }

        Fixture(int permitCapacity) {
            this(permitCapacity, false);
        }

        Fixture(int permitCapacity, boolean separateTupleEventLoop) {
            this(
                permitCapacity,
                separateTupleEventLoop,
                (replayContext, tuple) -> new TupleWriter.TransformedTuple<>(tuple)
            );
        }

        Fixture(
            int permitCapacity,
            boolean separateTupleEventLoop,
            TupleWriter.TupleTransformer<String> tupleTransformer
        ) {
            tupleEventLoop = separateTupleEventLoop ? new TestEventLoop(clock) : eventLoop;
            permitProvider = new TargetAttemptPermitProvider(
                permitCapacity,
                activePermits,
                TargetAttemptPermitProvider.Metrics.NOOP,
                fatalFailures::add
            );
            tupleWriter = new TupleWriter<>(
                tupleEventLoop,
                clock,
                Duration.ofSeconds(1),
                tupleTransformer,
                tupleSink,
                ignored -> {},
                fatalFailures::add,
                OutstandingOperationRegistry.CountHook.NOOP
            );
            owner = new TargetConnectionOwner<>(
                CONNECTION,
                eventLoop,
                clock,
                () -> Duration.between(Instant.EPOCH, clock.instant()).toNanos(),
                sourceTime -> sourceTime,
                preparer,
                retryPolicy,
                targetChannel,
                tupleWriter,
                (replayContext, result) -> {
                    tupleInputs.add(result);
                    return result.sourceRequest()
                        + "|"
                        + (result.transformationStatus().isSkipped()
                            ? "skipped"
                            : result.terminalTargetResponse().response())
                        + "|"
                        + describeFinal(result.finalSourceResponse());
                },
                new RequestReplayOwner.ResourceReleaser<>() {
                    @Override
                    public void releaseSourceRequest(String sourceRequest) {}

                    @Override
                    public void releasePreparedRequest(TestPrepared preparedRequest) {
                        preparedRequest.close();
                    }

                    @Override
                    public void releaseTargetResponse(String targetResponse) {}

                    @Override
                    public void releaseSourceResponse(String sourceResponse) {}
                },
                permitProvider,
                new RecordingLifecycleSink(
                    lifecycleEvents,
                    transitionHistory,
                    lifecycleAcceptances
                ),
                fatalFailures::add,
                (type, typeCount, totalCount) ->
                    transitionHistory.add("operations:" + totalCount)
            );
        }

        CompletionStage<TargetConnectionOwner.RequestAdmissionResult> admit(
            long ordinal,
            Instant firstByteTime
        ) {
            return owner.submit(
                new TargetConnectionOwner.AdmitReconstitutedRequest<>(
                    "source-" + ordinal,
                    requestContext(ordinal, firstByteTime)
                )
            );
        }

        private IReplayContexts.IRequestContext requestContext(
            long ordinal,
            Instant firstByteTime
        ) {
            var recordContext = replayContexts.createKafkaRecordContext(
                new KafkaRecordId(GENERATION, ordinal),
                0
            );
            var trafficContext = recordContext.createTrafficStreamContext(ordinal);
            var requestContext = trafficContext.createRequestContext(
                request(ordinal),
                firstByteTime
            );
            requestContext.onRequestReconstituted();
            return requestContext;
        }

        void completeSource(long ordinal, String response) {
            owner.submit(new TargetConnectionOwner.SourceResponseComplete<>(
                CONNECTION,
                GENERATION,
                request(ordinal),
                response,
                true
            ));
        }

        void incompleteSource(long ordinal) {
            owner.submit(new TargetConnectionOwner.SourceResponseIncomplete<>(
                CONNECTION,
                GENERATION,
                request(ordinal),
                "expired"
            ));
        }

        void unavailableForRetry(long ordinal) {
            owner.submit(new TargetConnectionOwner.SourceResponseUnavailableForRetry<>(
                CONNECTION,
                GENERATION,
                request(ordinal)
            ));
        }

        private static String describeFinal(
            RequestReplayOwner.FinalSourceResponse<String> response
        ) {
            return switch (response) {
                case RequestReplayOwner.CompleteFinalSourceResponse<String> complete ->
                    complete.response();
                case RequestReplayOwner.IncompleteFinalSourceResponse<String> incomplete ->
                    "incomplete:" + incomplete.reason();
            };
        }
    }

    static final class TestPrepared implements AutoCloseable {
        final String value;
        int closeCount;

        TestPrepared(String value) {
            this.value = value;
        }

        @Override
        public void close() {
            closeCount++;
        }
    }

    static final class FakePreparer
        implements RequestReplayOwner.RequestPreparer<String, TestPrepared> {

        final FakeClock clock;
        final Map<ReplayRequestId, CompletableFuture<RequestPreparationResult<TestPrepared>>>
            completions = new LinkedHashMap<>();
        final List<ReplayRequestId> begun = new ArrayList<>();
        final List<Instant> beginTimes = new ArrayList<>();

        FakePreparer(FakeClock clock) {
            this.clock = clock;
        }

        @Override
        public RequestReplayOwner.PreparationOperation<TestPrepared> begin(
            ReplayRequestId requestId,
            String sourceRequest,
            IReplayContexts.IRequestTransformationContext replayContext
        ) {
            begun.add(requestId);
            beginTimes.add(clock.instant());
            var completion = completions.computeIfAbsent(
                requestId,
                ignored -> new CompletableFuture<>()
            );
            return new RequestReplayOwner.PreparationOperation<>() {
                @Override
                public CompletionStage<RequestPreparationResult<TestPrepared>> completion() {
                    return completion;
                }

                @Override
                public void cancel(CancellationException cause) {
                    completion.complete(new RequestPreparationCancelled<>(cause));
                }
            };
        }

        void ready(long ordinal) {
            completions.computeIfAbsent(
                request(ordinal),
                ignored -> new CompletableFuture<>()
            ).complete(new RequestPreparationReady<>(
                new TestPrepared("prepared-" + ordinal)
            ));
        }

        void filtered(long ordinal) {
            completions.computeIfAbsent(
                request(ordinal),
                ignored -> new CompletableFuture<>()
            ).complete(new RequestPreparationReady<>(
                null,
                HttpRequestTransformationStatus.skipped()
            ));
        }
    }

    static final class FakeRetryPolicy
        implements RequestReplayOwner.RetryPolicy<String, String> {

        boolean requiresSourceResponse;
        Duration retryDelay = Duration.ofSeconds(1);
        final Queue<RetryDecision> decisions = new ArrayDeque<>();
        final List<RequestReplayOwner.RetrySourceResponse<String>> observedSources =
            new ArrayList<>();

        @Override
        public boolean requiresSourceResponse(String targetResponse) {
            return requiresSourceResponse;
        }

        @Override
        public RetryDecision decide(
            String targetResponse,
            RequestReplayOwner.RetrySourceResponse<String> sourceResponse
        ) {
            observedSources.add(sourceResponse);
            return decisions.isEmpty()
                ? new RetryDecision.TargetServerAttemptsFinished()
                : decisions.remove();
        }

        @Override
        public Duration retryDelay(int completedAttemptCount) {
            return retryDelay;
        }
    }

    static final class FakeTargetChannel
        implements TargetChannelPort<TestPrepared, String> {

        final List<AttemptRecord> attempts = new ArrayList<>();
        final List<ConnectionProcessingId> closes = new ArrayList<>();
        CompletableFuture<Void> closeCompletion = CompletableFuture.completedFuture(null);

        @Override
        public Attempt<String> startAttempt(AttemptInput<TestPrepared> input) {
            var record = new AttemptRecord(input);
            attempts.add(record);
            return record;
        }

        @Override
        public CompletionStage<Void> close(ConnectionProcessingId connectionProcessingId) {
            closes.add(connectionProcessingId);
            return closeCompletion;
        }

        AttemptRecord attempt(int index) {
            return attempts.get(index);
        }

        final class AttemptRecord implements Attempt<String> {
            final AttemptInput<TestPrepared> input;
            final CompletableFuture<TargetAttemptOutcome<String>> outcome =
                new CompletableFuture<>();
            final CompletableFuture<Void> abort = new CompletableFuture<>();
            int abortCalls;
            boolean firstWriteReported;
            boolean finalWriteReported;

            AttemptRecord(AttemptInput<TestPrepared> input) {
                this.input = input;
            }

            @Override
            public CompletionStage<TargetAttemptOutcome<String>> outcome() {
                return outcome;
            }

            @Override
            public CompletionStage<Void> abort(CancellationException cause) {
                abortCalls++;
                return abort;
            }

            void firstWrite() {
                firstWriteReported = true;
                input.writeMilestones().firstTargetWriteSubmitted(input.attemptNumber());
            }

            void finalWrite() {
                finalWriteReported = true;
                input.writeMilestones().finalTargetWriteSubmitted(input.attemptNumber());
            }

            void targetResponse(String response) {
                if (!firstWriteReported) {
                    firstWrite();
                }
                if (!finalWriteReported) {
                    finalWrite();
                }
                outcome.complete(new TargetAttemptOutcome.TargetResponseObtained<>(response));
            }

            void noResponse() {
                outcome.complete(new TargetAttemptOutcome.NoTargetResponseObtained<>(
                    new TargetAttemptOutcome.NoTargetResponseDiagnostic(
                        TargetAttemptOutcome.NoTargetResponseKind.TRANSPORT_FAILURE,
                        "connection closed without a response"
                    )
                ));
            }
        }
    }

    static final class FakeTupleSink
        implements TupleWriter.PhysicalTupleSink<String> {

        final List<String> writes = new ArrayList<>();
        final Queue<CompletableFuture<Void>> completions = new ArrayDeque<>();

        @Override
        public CompletionStage<Void> write(
            IReplayContexts.ITupleHandlingContext replayContext,
            String tuple
        ) {
            writes.add(tuple);
            var completion = new CompletableFuture<Void>();
            completions.add(completion);
            return completion;
        }

        void durableNext() {
            completions.remove().complete(null);
        }

        void failNext() {
            completions.remove().completeExceptionally(
                new IllegalStateException("sink unavailable")
            );
        }
    }

    private record RecordingLifecycleSink(
        List<String> events,
        List<String> transitionHistory,
        Map<String, CompletableFuture<Void>> acceptances
    ) implements TargetConnectionOwner.LifecycleSink {

        @Override
        public CompletionStage<Void> connectionRequestFinished(
            PartitionGenerationId partitionGenerationId,
            ConnectionProcessingId connectionProcessingId,
            ReplayRequestId requestId
        ) {
            return record("turn:" + requestId.capturedRequestOrdinal());
        }

        @Override
        public CompletionStage<Void> requestProcessingFinished(
            PartitionGenerationId partitionGenerationId,
            ConnectionProcessingId connectionProcessingId,
            ReplayRequestId requestId
        ) {
            return record("processing:" + requestId.capturedRequestOrdinal());
        }

        @Override
        public CompletionStage<Void> connectionOwnerFinished(
            PartitionGenerationId partitionGenerationId,
            ConnectionProcessingId connectionProcessingId
        ) {
            return record("owner-finished");
        }

        @Override
        public CompletionStage<Void> connectionCleanupFinished(
            PartitionGenerationId partitionGenerationId,
            ConnectionProcessingId connectionProcessingId
        ) {
            return record("cleanup-finished");
        }

        private CompletionStage<Void> record(String event) {
            events.add(event);
            transitionHistory.add("lifecycle:" + event);
            return acceptances.computeIfAbsent(
                event,
                ignored -> CompletableFuture.completedFuture(null)
            );
        }
    }
}

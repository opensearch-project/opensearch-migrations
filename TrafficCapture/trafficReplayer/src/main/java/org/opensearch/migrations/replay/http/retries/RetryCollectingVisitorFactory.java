package org.opensearch.migrations.replay.http.retries;

// REBUILD-LIMBO(G5) -- nothing in this file is live yet. Javadoc is left outside the marked
// regions so it needs no escaping and keeps its blame; it documents code that is not compiled.
// Resolve each region to dead, keep, or refactor deliberately. If a member is deleted, delete its
// javadoc with it. See AGENTS.md section 8a.
// Cascade from the left-behind legacy set. Unresolved: IRequestResponsePacketPair IRetryVisitorFactory RequestSenderOrchestrator . Carried byte-identical so the behaviour stays enumerable; its milestone strips the legacy references and un-marks it.
// Un-mark a member by deleting the delimiter lines around it and splitting this region; the
// code between them is verbatim, so blame survives. Read this before writing anything new

// REBUILD-LIMBO-START(G5)
/*

import java.util.concurrent.atomic.AtomicReference;

import org.opensearch.migrations.replay.AggregatedRawResponse;
import org.opensearch.migrations.replay.IRequestResponsePacketPair;
import org.opensearch.migrations.replay.RequestSenderOrchestrator;
import org.opensearch.migrations.replay.TransformedTargetRequestAndResponseList;
import org.opensearch.migrations.replay.datatypes.ByteBufListProducer;
import org.opensearch.migrations.replay.datatypes.TransformedOutputAndResult;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.TargetAttemptOutcome;
import org.opensearch.migrations.utils.TextTrackedFuture;
import org.opensearch.migrations.utils.TrackedFuture;

public class RetryCollectingVisitorFactory implements IRetryVisitorFactory<TransformedTargetRequestAndResponseList> {
    private final RequestRetryEvaluator shouldRetry;

    public RetryCollectingVisitorFactory(RequestRetryEvaluator shouldRetry) {
        this.shouldRetry = shouldRetry;
    }

    @Override
    public RequestSenderOrchestrator.RetryVisitor<TransformedTargetRequestAndResponseList>
    getRetryCheckVisitor(TransformedOutputAndResult<ByteBufListProducer> transformedResult,
        TrackedFuture<String, ? extends IRequestResponsePacketPair> finishedAccumulatingResponseFuture) {
        return new RetryCollectorVisitor(transformedResult, finishedAccumulatingResponseFuture);
    }

    private final class RetryCollectorVisitor
        implements RequestSenderOrchestrator.RetryVisitor<TransformedTargetRequestAndResponseList> {
        private final AtomicReference<TransformedTargetRequestAndResponseList> collectorRef;
        private final TrackedFuture<String, ? extends IRequestResponsePacketPair> finishedAccumulatingResponseFuture;

        private RetryCollectorVisitor(
            TransformedOutputAndResult<ByteBufListProducer> transformedResult,
            TrackedFuture<String, ? extends IRequestResponsePacketPair> finishedAccumulatingResponseFuture
        ) {
            collectorRef = new AtomicReference<>(new TransformedTargetRequestAndResponseList(
                transformedResult.transformedOutput.retainDiagnosticCopy(),
                transformedResult.transformationStatus
            ));
            this.finishedAccumulatingResponseFuture = finishedAccumulatingResponseFuture;
        }

        @Override
        public TrackedFuture<String, RequestSenderOrchestrator.DeterminedTransformedResponse<
            TransformedTargetRequestAndResponseList>> visit(
            io.netty.buffer.ByteBuf requestBytes,
            TargetAttemptOutcome<AggregatedRawResponse> outcome
        ) {
            return outcome.visit(new TargetAttemptOutcome.Visitor<>() {
                @Override
                public TrackedFuture<String, RequestSenderOrchestrator.DeterminedTransformedResponse<
                    TransformedTargetRequestAndResponseList>> onTargetResponseObtained(
                    TargetAttemptOutcome.TargetResponseObtained<AggregatedRawResponse> obtained
                ) {
                    return evaluateTargetResponse(requestBytes, obtained.response());
                }

                @Override
                public TrackedFuture<String, RequestSenderOrchestrator.DeterminedTransformedResponse<
                    TransformedTargetRequestAndResponseList>> onNoTargetResponseObtained(
                    TargetAttemptOutcome.NoTargetResponseObtained<AggregatedRawResponse> notObtained
                ) {
                    return retryAfterNoResponse(notObtained);
                }
            });
        }

        private TrackedFuture<String, RequestSenderOrchestrator.DeterminedTransformedResponse<
            TransformedTargetRequestAndResponseList>> evaluateTargetResponse(
            io.netty.buffer.ByteBuf requestBytes,
            AggregatedRawResponse aggregatedResponse
        ) {
            var collector = collectorRef.get();
            if (collector == null) {
                return TextTrackedFuture.failedFuture(
                    new IllegalStateException("retry collector ownership was already transferred"),
                    () -> "retry visitor was invoked after its result was transferred"
                );
            }
            collector.addAttemptOutcome(
                new TargetAttemptOutcome.TargetResponseObtained<>(aggregatedResponse)
            );
            return shouldRetry.shouldRetry(
                requestBytes,
                collector.responses(),
                aggregatedResponse,
                finishedAccumulatingResponseFuture
            ).thenCompose(
                directive -> completeDecision(collector, directive),
                () -> "determining if we should retry or just return the response now"
            );
        }

        private TrackedFuture<String, RequestSenderOrchestrator.DeterminedTransformedResponse<
            TransformedTargetRequestAndResponseList>> retryAfterNoResponse(
            TargetAttemptOutcome.NoTargetResponseObtained<AggregatedRawResponse> outcome
        ) {
            var collector = collectorRef.get();
            if (collector == null) {
                return TextTrackedFuture.failedFuture(
                    new IllegalStateException("retry collector ownership was already transferred"),
                    () -> "retry visitor was invoked after its result was transferred"
                );
            }
            collector.addAttemptOutcome(outcome);
            return TextTrackedFuture.completedFuture(
                new RequestSenderOrchestrator.DeterminedTransformedResponse<>(
                    RequestSenderOrchestrator.RetryDirective.RETRY,
                    null
                ),
                () -> "Returning a future to retry because no target response was obtained: " + outcome.reason()
            );
        }

        private TrackedFuture<String, RequestSenderOrchestrator.DeterminedTransformedResponse<
            TransformedTargetRequestAndResponseList>> completeDecision(
            TransformedTargetRequestAndResponseList collector,
            RequestSenderOrchestrator.RetryDirective directive
        ) {
            if (collectorRef.get() != collector) {
                return TextTrackedFuture.failedFuture(
                    new IllegalStateException("retry collector was closed before its decision completed"),
                    () -> "retry decision completed after its collector was closed"
                );
            }
            if (directive != RequestSenderOrchestrator.RetryDirective.DONE) {
                return TextTrackedFuture.completedFuture(
                    new RequestSenderOrchestrator.DeterminedTransformedResponse<>(directive, null),
                    () -> "Returning the retry directive"
                );
            }
            if (!collectorRef.compareAndSet(collector, null)) {
                return TextTrackedFuture.failedFuture(
                    new IllegalStateException("retry collector ownership transfer lost a race"),
                    () -> "retry collector could not transfer its terminal result"
                );
            }
            return TextTrackedFuture.completedFuture(
                new RequestSenderOrchestrator.DeterminedTransformedResponse<>(
                    directive,
                    collector,
                    TransformedTargetRequestAndResponseList::close
                ),
                () -> "Returning the terminal target response"
            );
        }

        @Override
        public void close() {
            var collector = collectorRef.getAndSet(null);
            if (collector != null) {
                collector.close();
            }
        }
    }
}

*/
// REBUILD-LIMBO-END(G5)
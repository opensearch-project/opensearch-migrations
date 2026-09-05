package org.opensearch.migrations.replay.http.retries;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;

import org.opensearch.migrations.replay.IRequestResponsePacketPair;
import org.opensearch.migrations.replay.RequestSenderOrchestrator;
import org.opensearch.migrations.replay.TransformedTargetRequestAndResponseList;
import org.opensearch.migrations.replay.datatypes.ByteBufListProducer;
import org.opensearch.migrations.replay.datatypes.TransformedOutputAndResult;
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
        var collectorRef = new AtomicReference<>(new TransformedTargetRequestAndResponseList(
            transformedResult.transformedOutput.retainDiagnosticCopy(),
            transformedResult.transformationStatus));
        return new RequestSenderOrchestrator.RetryVisitor<>() {
            @Override
            public TrackedFuture<String, RequestSenderOrchestrator.DeterminedTransformedResponse<
                TransformedTargetRequestAndResponseList>> visit(
                io.netty.buffer.ByteBuf requestBytes,
                org.opensearch.migrations.replay.AggregatedRawResponse aggResponse,
                Throwable t
            ) {
                if (t != null) {
                    return TextTrackedFuture.completedFuture(
                        new RequestSenderOrchestrator.DeterminedTransformedResponse<>(
                            RequestSenderOrchestrator.RetryDirective.RETRY,
                            null),
                        () -> "Returning a future to retry due to an unknown exception: " + t);
                }
                assert (aggResponse != null);
                var collector = collectorRef.get();
                if (collector == null) {
                    return TextTrackedFuture.failedFuture(
                        new IllegalStateException("retry collector ownership was already transferred"),
                        () -> "retry visitor was invoked after its result was transferred"
                    );
                }
                collector.addResponse(aggResponse);
                return shouldRetry.shouldRetry(requestBytes, Collections.unmodifiableList(collector.getResponseList()),
                        aggResponse, finishedAccumulatingResponseFuture)
                    .thenCompose(d -> {
                        if (collectorRef.get() != collector) {
                            return TextTrackedFuture.failedFuture(
                                new IllegalStateException("retry collector was closed before its decision completed"),
                                () -> "retry decision completed after its collector was closed"
                            );
                        }
                        if (d == RequestSenderOrchestrator.RetryDirective.DONE) {
                            if (!collectorRef.compareAndSet(collector, null)) {
                                return TextTrackedFuture.failedFuture(
                                    new IllegalStateException("retry collector ownership transfer lost a race"),
                                    () -> "retry collector could not transfer its terminal result"
                                );
                            }
                            return TextTrackedFuture.completedFuture(
                                new RequestSenderOrchestrator.DeterminedTransformedResponse<>(
                                    d,
                                    collector,
                                    TransformedTargetRequestAndResponseList::close
                                ),
                                () -> "Returning the terminal target response"
                            );
                        }
                        return TextTrackedFuture.completedFuture(
                            new RequestSenderOrchestrator.DeterminedTransformedResponse<>(d, null),
                            () -> "Returning the retry directive"
                        );
                    }, () -> "determining if we should retry or just return the response now");
            }

            @Override
            public void close() {
                var collector = collectorRef.getAndSet(null);
                if (collector != null) {
                    collector.close();
                }
            }
        };
    }
}

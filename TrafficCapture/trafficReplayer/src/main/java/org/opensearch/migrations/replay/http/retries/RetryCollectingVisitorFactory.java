package org.opensearch.migrations.replay.http.retries;

import java.util.Collections;

import org.opensearch.migrations.replay.IRequestResponsePacketPair;
import org.opensearch.migrations.replay.RequestSenderOrchestrator;
import org.opensearch.migrations.replay.TransformedTargetRequestAndResponseList;
import org.opensearch.migrations.replay.datatypes.ByteBufList;
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
    getRetryCheckVisitor(TransformedOutputAndResult<ByteBufList> transformedResult,
                         TrackedFuture<String, ? extends IRequestResponsePacketPair> finishedAccumulatingResponseFuture) {
        var collector = new TransformedTargetRequestAndResponseList(
            transformedResult.transformedOutput,
            transformedResult.transformationStatus);
        return (requestBytes, aggResponse, t) -> {
            if (t != null) {
                return TextTrackedFuture.completedFuture(
                    new RequestSenderOrchestrator.DeterminedTransformedResponse<>(
                        RequestSenderOrchestrator.RetryDirective.RETRY,
                        null),
                    () -> "Returning a future to retry due to an unknown exception: " + t);
            } else {
                assert (aggResponse != null);
                collector.addResponse(aggResponse);
                return shouldRetry.shouldRetry(requestBytes, Collections.unmodifiableList(collector.getResponseList()),
                        aggResponse, finishedAccumulatingResponseFuture)
                    .thenCompose(d -> TextTrackedFuture.completedFuture(
                            new RequestSenderOrchestrator.DeterminedTransformedResponse<>(d, collector),
                            () -> "Returning a future to retry due to a connection exception"),
                        () -> "determining if we should retry or just return the response now");
            }
        };
    }
}

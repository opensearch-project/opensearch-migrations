package org.opensearch.migrations.replay.http.retries;

import org.opensearch.migrations.replay.AggregatedRawResponse;
import org.opensearch.migrations.replay.IRequestResponsePacketPair;
import org.opensearch.migrations.replay.RequestSenderOrchestrator;
import org.opensearch.migrations.replay.datatypes.ByteBufList;
import org.opensearch.migrations.replay.datatypes.TransformedOutputAndResult;
import org.opensearch.migrations.utils.TextTrackedFuture;
import org.opensearch.migrations.utils.TrackedFuture;

import io.netty.buffer.ByteBuf;

public class NoRetryEvaluatorFactory implements IRetryVisitorFactory<AggregatedRawResponse> {

    public static class NoRetryVisitor
        implements RequestSenderOrchestrator.RetryVisitor<AggregatedRawResponse> {
        @Override
        public TrackedFuture<String, RequestSenderOrchestrator.DeterminedTransformedResponse<AggregatedRawResponse>>
        visit(ByteBuf requestBytes, AggregatedRawResponse arr, Throwable t) {
            return TextTrackedFuture.completedFuture(new RequestSenderOrchestrator.DeterminedTransformedResponse<>(
                RequestSenderOrchestrator.RetryDirective.DONE,
                arr), () -> "returning DONE immediately because this NoRetry factory never retries");
        }
    }

    @Override
    public RequestSenderOrchestrator.RetryVisitor<AggregatedRawResponse>
    getRetryCheckVisitor(TransformedOutputAndResult<ByteBufList> transformedResult,
                         TrackedFuture<String, ? extends IRequestResponsePacketPair> accumulationResponseFuture) {
        return new NoRetryVisitor();
    }
}

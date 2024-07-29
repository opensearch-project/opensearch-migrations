package org.opensearch.migrations.replay.http.retries;

import io.netty.buffer.ByteBuf;
import org.opensearch.migrations.replay.AggregatedRawResponse;
import org.opensearch.migrations.replay.IRequestResponsePacketPair;
import org.opensearch.migrations.replay.RequestResponsePacketPair;
import org.opensearch.migrations.replay.RequestSenderOrchestrator;
import org.opensearch.migrations.replay.datatypes.ByteBufList;
import org.opensearch.migrations.replay.datatypes.TransformedOutputAndResult;
import org.opensearch.migrations.replay.util.TextTrackedFuture;
import org.opensearch.migrations.replay.util.TrackedFuture;

import java.util.Collections;
import java.util.List;

public class NoRetryEvaluatorFactory implements IRetryVisitorFactory<AggregatedRawResponse> {

    public static class NoRetryVisitor
        implements RequestSenderOrchestrator.RepeatedAggregatedRawResponseVisitor<AggregatedRawResponse> {
        @Override
        public TrackedFuture<String, RequestSenderOrchestrator.DeterminedTransformedResponse<AggregatedRawResponse>>
        visit(ByteBuf requestBytes, AggregatedRawResponse arr, Throwable t) {
            return TextTrackedFuture.completedFuture(new RequestSenderOrchestrator.DeterminedTransformedResponse<>(
                RequestSenderOrchestrator.RetryDirective.DONE,
                arr), () -> "returning DONE immediately because this NoRetry factory never retries");
        }
    }

    @Override
    public RequestSenderOrchestrator.RepeatedAggregatedRawResponseVisitor<AggregatedRawResponse>
    getRetryCheckVisitor(TransformedOutputAndResult<ByteBufList> transformedResult,
                         TrackedFuture<String, ? extends IRequestResponsePacketPair> accumulationResponseFuture) {
        return new NoRetryVisitor();
    }
}
package org.opensearch.migrations.replay.http.retries;

import java.util.List;

import org.opensearch.migrations.replay.AggregatedRawResponse;
import org.opensearch.migrations.replay.IRequestResponsePacketPair;
import org.opensearch.migrations.replay.RequestSenderOrchestrator;
import org.opensearch.migrations.utils.TrackedFuture;

import io.netty.buffer.ByteBuf;

public interface RequestRetryEvaluator {

    TrackedFuture<String, RequestSenderOrchestrator.RetryDirective>
    shouldRetry(
        ByteBuf targetRequestBytes,
        List<AggregatedRawResponse> previousResponses,
        AggregatedRawResponse currentResponse,
        TrackedFuture<String, ? extends IRequestResponsePacketPair> reconstructedSourceTransactionFuture);
}

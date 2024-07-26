package org.opensearch.migrations.replay.http.retries;

import io.netty.buffer.ByteBuf;
import org.opensearch.migrations.replay.AggregatedRawResponse;
import org.opensearch.migrations.replay.IRequestResponsePacketPair;
import org.opensearch.migrations.replay.RequestResponsePacketPair;
import org.opensearch.migrations.replay.RequestSenderOrchestrator;
import org.opensearch.migrations.replay.TransformedTargetRequestAndResponseList;
import org.opensearch.migrations.replay.util.TrackedFuture;

import java.util.List;

public interface RequestRetryEvaluator {

    TrackedFuture<String, RequestSenderOrchestrator.RetryDirective>
    apply(
        ByteBuf targetRequestBytes,
        List<AggregatedRawResponse> previousResponses,
        AggregatedRawResponse currentResponse,
        TrackedFuture<String, ? extends IRequestResponsePacketPair> reconstructedSourceTransactionFuture);
}

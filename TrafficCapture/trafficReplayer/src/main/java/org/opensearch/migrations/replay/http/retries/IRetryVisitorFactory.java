package org.opensearch.migrations.replay.http.retries;

import org.opensearch.migrations.replay.IRequestResponsePacketPair;
import org.opensearch.migrations.replay.RequestSenderOrchestrator;
import org.opensearch.migrations.replay.datatypes.ByteBufListProducer;
import org.opensearch.migrations.replay.datatypes.TransformedOutputAndResult;
import org.opensearch.migrations.utils.TrackedFuture;

public interface IRetryVisitorFactory<T> {
    RequestSenderOrchestrator.RetryVisitor<T>
    getRetryCheckVisitor(TransformedOutputAndResult<ByteBufListProducer> transformedResult,
                         TrackedFuture<String, ? extends IRequestResponsePacketPair> finishedAccumulatingResponseFuture);
}

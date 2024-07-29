package org.opensearch.migrations.replay.http.retries;

import java.util.Collections;

import org.opensearch.migrations.replay.IRequestResponsePacketPair;
import org.opensearch.migrations.replay.RequestResponsePacketPair;
import org.opensearch.migrations.replay.RequestSenderOrchestrator;
import org.opensearch.migrations.replay.TransformedTargetRequestAndResponseList;
import org.opensearch.migrations.replay.datatypes.ByteBufList;
import org.opensearch.migrations.replay.datatypes.TransformedOutputAndResult;
import org.opensearch.migrations.replay.util.TrackedFuture;

public interface IRetryVisitorFactory<T> {
    RequestSenderOrchestrator.RepeatedAggregatedRawResponseVisitor<T>
    getRetryCheckVisitor(TransformedOutputAndResult<ByteBufList> transformedResult,
                         TrackedFuture<String, ? extends IRequestResponsePacketPair> finishedAccumulatingResponseFuture);
}

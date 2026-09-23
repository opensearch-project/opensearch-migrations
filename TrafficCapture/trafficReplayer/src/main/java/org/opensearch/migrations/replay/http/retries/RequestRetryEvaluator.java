package org.opensearch.migrations.replay.http.retries;

// REBUILD-LIMBO(G5) -- nothing in this file is live yet. Javadoc is left outside the marked
// regions so it needs no escaping and keeps its blame; it documents code that is not compiled.
// Resolve each region to dead, keep, or refactor deliberately. If a member is deleted, delete its
// javadoc with it. See AGENTS.md section 8a.
// Cascade from the left-behind legacy set. Unresolved: IRequestResponsePacketPair RequestSenderOrchestrator . Carried byte-identical so the behaviour stays enumerable; its milestone strips the legacy references and un-marks it.
// Un-mark a member by deleting the delimiter lines around it and splitting this region; the
// code between them is verbatim, so blame survives. Read this before writing anything new

// REBUILD-LIMBO-START(G5)
/*

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

*/
// REBUILD-LIMBO-END(G5)
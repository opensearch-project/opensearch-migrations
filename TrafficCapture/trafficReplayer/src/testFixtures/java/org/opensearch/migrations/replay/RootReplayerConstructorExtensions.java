package org.opensearch.migrations.replay;

// REBUILD-LIMBO(G10) -- nothing in this file is live yet. Javadoc is left outside the marked
// regions so it needs no escaping and keeps its blame; it documents code that is not compiled.
// Resolve each region to dead, keep, or refactor deliberately. If a member is deleted, delete its
// javadoc with it. See AGENTS.md section 8a.
// Test carried byte-identical. Unresolved: BufferedFlowController ClientConnectionPool IJsonTransformer IRootReplayerContext TrafficReplayerTopLevel . Per AGENTS.md section 4 an inherited test may stay broken while the architectures are partly connected; this one is restored by the milestone that rebuilds its subject, keeping its assertions conceptually stable while changing the mechanics.
// Un-mark a member by deleting the delimiter lines around it and splitting this region; the
// code between them is verbatim, so blame survives. Read this before writing anything new

// REBUILD-LIMBO-START(G10)
/*

import javax.net.ssl.SSLException;

import java.net.URI;
import java.time.Duration;

import org.opensearch.migrations.ExceptionTypeAllowlist;
import org.opensearch.migrations.replay.http.retries.BulkItemErrorClassifier;
import org.opensearch.migrations.replay.tracing.IRootReplayerContext;
import org.opensearch.migrations.replay.traffic.source.BufferedFlowController;
import org.opensearch.migrations.transform.IAuthTransformerFactory;
import org.opensearch.migrations.transform.IJsonTransformer;

public class RootReplayerConstructorExtensions extends TrafficReplayerTopLevel {

    public RootReplayerConstructorExtensions(
        IRootReplayerContext topContext,
        URI uri,
        IAuthTransformerFactory authTransformerFactory,
        IJsonTransformer jsonTransformer,
        ClientConnectionPool clientConnectionPool,
        ReplayProcessFatalHandler.ProcessTerminator processTerminator
    ) {
        this(
            topContext,
            uri,
            authTransformerFactory,
            jsonTransformer,
            clientConnectionPool,
            1024,
            processTerminator
        );
    }

    public RootReplayerConstructorExtensions(
        IRootReplayerContext topContext,
        URI uri,
        IAuthTransformerFactory authTransformer,
        IJsonTransformer jsonTransformer,
        ClientConnectionPool clientConnectionPool,
        int maxConcurrentRequests,
        ReplayProcessFatalHandler.ProcessTerminator processTerminator
    ) {
        this(
            topContext,
            uri,
            authTransformer,
            jsonTransformer,
            clientConnectionPool,
            maxConcurrentRequests,
            new TrafficReplayerTopLevel.ConcurrentHashMapWorkTracker<>(),
            processTerminator
        );
    }

    public RootReplayerConstructorExtensions(
        IRootReplayerContext context,
        URI serverUri,
        IAuthTransformerFactory authTransformerFactory,
        IJsonTransformer jsonTransformer,
        ClientConnectionPool clientConnectionPool,
        int maxConcurrentOutstandingRequests,
        TrafficReplayerTopLevel.IStreamableWorkTracker<Void> workTracker,
        ReplayProcessFatalHandler.ProcessTerminator processTerminator
    ) {
        super(
            context,
            serverUri,
            authTransformerFactory,
            () -> jsonTransformer,
            clientConnectionPool,
            maxConcurrentOutstandingRequests,
            workTracker,
            new BulkItemErrorClassifier(),
            ExceptionTypeAllowlist.empty(),
            processTerminator
        );
    }

    public static ReplayEngineFactory makeReplayEngineFactory(
        BufferedFlowController flowController,
        ReplayEngineFactory.Dependencies dependencies
    ) {
        return new ReplayEngineFactory(
            Duration.ofSeconds(70),
            flowController,
            new TimeShifter(10 * 1000),
            dependencies
        );
    }

    public static ClientConnectionPool makeNettyPacketConsumerConnectionPool(URI serverUri) throws SSLException {
        return makeNettyPacketConsumerConnectionPool(serverUri, null);
    }

    public static ClientConnectionPool makeNettyPacketConsumerConnectionPool(URI serverUri, String poolPrefix)
        throws SSLException
    {
        return makeNettyPacketConsumerConnectionPool(serverUri, true, 0, poolPrefix);
    }

    public static ClientConnectionPool makeNettyPacketConsumerConnectionPool(URI serverUri, int numSendingThreads)
        throws SSLException {
        return makeNettyPacketConsumerConnectionPool(serverUri, true, numSendingThreads, null);
    }
}

*/
// REBUILD-LIMBO-END(G10)
package org.opensearch.migrations.replay;

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

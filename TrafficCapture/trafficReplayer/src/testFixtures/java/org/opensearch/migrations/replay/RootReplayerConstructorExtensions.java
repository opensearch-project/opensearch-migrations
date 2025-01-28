package org.opensearch.migrations.replay;

import javax.net.ssl.SSLException;

import java.net.URI;
import java.time.Duration;

import org.opensearch.migrations.replay.tracing.IRootReplayerContext;
import org.opensearch.migrations.replay.traffic.source.BufferedFlowController;
import org.opensearch.migrations.replay.traffic.source.TrafficStreamLimiter;
import org.opensearch.migrations.transform.IAuthTransformerFactory;
import org.opensearch.migrations.transform.IJsonTransformer;

public class RootReplayerConstructorExtensions extends TrafficReplayerTopLevel {

    public RootReplayerConstructorExtensions(
        IRootReplayerContext topContext,
        URI uri,
        IAuthTransformerFactory authTransformerFactory,
        IJsonTransformer jsonTransformer,
        ClientConnectionPool clientConnectionPool
    ) {
        this(topContext, uri, authTransformerFactory, jsonTransformer, clientConnectionPool, 1024);
    }

    public RootReplayerConstructorExtensions(
        IRootReplayerContext topContext,
        URI uri,
        IAuthTransformerFactory authTransformer,
        IJsonTransformer jsonTransformer,
        ClientConnectionPool clientConnectionPool,
        int maxConcurrentRequests
    ) {
        this(
            topContext,
            uri,
            authTransformer,
            jsonTransformer,
            clientConnectionPool,
            maxConcurrentRequests,
            new TrafficReplayerTopLevel.ConcurrentHashMapWorkTracker<>()
        );
    }

    public RootReplayerConstructorExtensions(
        IRootReplayerContext context,
        URI serverUri,
        IAuthTransformerFactory authTransformerFactory,
        IJsonTransformer jsonTransformer,
        ClientConnectionPool clientConnectionPool,
        int maxConcurrentOutstandingRequests,
        TrafficReplayerTopLevel.IStreamableWorkTracker<Void> workTracker
    ) {
        super(
            context,
            serverUri,
            authTransformerFactory,
            () -> jsonTransformer,
            clientConnectionPool,
            new TrafficStreamLimiter(maxConcurrentOutstandingRequests),
            workTracker
        );
    }

    public static ReplayEngineFactory makeReplayEngineFactory(BufferedFlowController flowController) {
        return new ReplayEngineFactory(Duration.ofSeconds(70), flowController, new TimeShifter(10 * 1000));
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

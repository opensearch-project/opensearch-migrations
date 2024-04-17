package org.opensearch.migrations.replay;

import org.opensearch.migrations.replay.tracing.IRootReplayerContext;
import org.opensearch.migrations.replay.traffic.source.TrafficStreamLimiter;
import org.opensearch.migrations.transform.IAuthTransformerFactory;
import org.opensearch.migrations.transform.IJsonTransformer;

import javax.net.ssl.SSLException;
import java.net.URI;
import java.time.Duration;

public class RootReplayerConstructorExtensions extends TrafficReplayerTopLevel {

    public static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(30);

    public RootReplayerConstructorExtensions(IRootReplayerContext topContext,
                                             URI uri,
                                             IAuthTransformerFactory authTransformerFactory,
                                             IJsonTransformer jsonTransformer,
                                             ClientConnectionPool clientConnectionPool) {
        this(topContext, uri, authTransformerFactory, jsonTransformer, clientConnectionPool, 1024);
    }

    public RootReplayerConstructorExtensions(IRootReplayerContext topContext,
                                             URI uri,
                                             IAuthTransformerFactory authTransformer,
                                             IJsonTransformer jsonTransformer,
                                             ClientConnectionPool clientConnectionPool,
                                             int maxConcurrentRequests) {
        this(topContext, uri, authTransformer, jsonTransformer, clientConnectionPool,
                maxConcurrentRequests, new TrafficReplayerTopLevel.ConcurrentHashMapWorkTracker<>());
    }

    public RootReplayerConstructorExtensions(IRootReplayerContext context,
                                             URI serverUri,
                                             IAuthTransformerFactory authTransformerFactory,
                                             IJsonTransformer jsonTransformer,
                                             ClientConnectionPool clientConnectionPool,
                                             int maxConcurrentOutstandingRequests,
                                             TrafficReplayerTopLevel.IStreamableWorkTracker<Void> workTracker) {
        super(context, serverUri, authTransformerFactory, jsonTransformer, clientConnectionPool,
                new TrafficStreamLimiter(maxConcurrentOutstandingRequests), workTracker);
    }

    public static ClientConnectionPool makeClientConnectionPool(URI serverUri) throws SSLException {
        return makeClientConnectionPool(serverUri, null, RESPONSE_TIMEOUT);
    }

    public static ClientConnectionPool makeClientConnectionPool(URI serverUri, String poolPrefix) throws SSLException {
        return makeClientConnectionPool(serverUri, poolPrefix, RESPONSE_TIMEOUT);
    }

    public static ClientConnectionPool makeClientConnectionPool(URI serverUri,
                                                                String poolPrefix,
                                                                Duration timeout) throws SSLException {
        return makeClientConnectionPool(serverUri, true, 0, poolPrefix, timeout);
    }

    public static ClientConnectionPool makeClientConnectionPool(URI serverUri,
                                                                int numSendingThreads) throws SSLException {
        return makeClientConnectionPool(serverUri, numSendingThreads, RESPONSE_TIMEOUT);
    }

    public static ClientConnectionPool makeClientConnectionPool(URI serverUri,
                                                                int numSendingThreads,
                                                                Duration timeout) throws SSLException {
        return makeClientConnectionPool(serverUri, true, numSendingThreads, null, timeout);
    }
}

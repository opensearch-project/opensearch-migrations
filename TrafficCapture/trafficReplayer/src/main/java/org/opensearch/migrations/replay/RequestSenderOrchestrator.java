package org.opensearch.migrations.replay;

import io.netty.channel.ChannelFuture;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.replay.datahandlers.NettyPacketToHttpConsumer;
import org.opensearch.migrations.replay.datatypes.UniqueRequestKey;

import java.util.concurrent.ExecutionException;

@Slf4j
public class RequestSenderOrchestrator {
    private final int maxRetriesForNewConnection;
    public final ClientConnectionPool clientConnectionPool;

    public RequestSenderOrchestrator(ClientConnectionPool clientConnectionPool, int maxRetriesForNewConnection) {
        this.maxRetriesForNewConnection = maxRetriesForNewConnection;
        this.clientConnectionPool = clientConnectionPool;
    }

    public NettyPacketToHttpConsumer create(UniqueRequestKey requestKey) {
        return new NettyPacketToHttpConsumer(clientConnectionPool.get(requestKey, maxRetriesForNewConnection),
                requestKey.toString());
    }

}

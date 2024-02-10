package org.opensearch.migrations.replay;

import org.opensearch.migrations.replay.datahandlers.IPacketFinalizingConsumer;
import org.opensearch.migrations.replay.tracing.IReplayContexts;

public interface PacketConsumerFactory<R> {
    IPacketFinalizingConsumer<R> create(IReplayContexts.IReplayerHttpTransactionContext context);
}

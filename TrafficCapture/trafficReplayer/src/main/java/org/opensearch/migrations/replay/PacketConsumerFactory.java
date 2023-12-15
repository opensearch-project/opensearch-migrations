package org.opensearch.migrations.replay;

import org.opensearch.migrations.replay.datahandlers.IPacketFinalizingConsumer;
import org.opensearch.migrations.replay.datatypes.UniqueReplayerRequestKey;
import org.opensearch.migrations.replay.tracing.Contexts;
import org.opensearch.migrations.replay.tracing.IContexts;

public interface PacketConsumerFactory<R> {
    IPacketFinalizingConsumer<R> create(UniqueReplayerRequestKey requestKey,
                                        IContexts.IReplayerHttpTransactionContext context);
}

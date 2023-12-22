package org.opensearch.migrations.replay;

import org.opensearch.migrations.replay.datahandlers.IPacketFinalizingConsumer;
import org.opensearch.migrations.replay.datatypes.UniqueReplayerRequestKey;
import org.opensearch.migrations.replay.tracing.IReplayContexts;

public interface PacketConsumerFactory<R> {
    IPacketFinalizingConsumer<R> create(UniqueReplayerRequestKey requestKey,
                                        IReplayContexts.IReplayerHttpTransactionContext context);
}

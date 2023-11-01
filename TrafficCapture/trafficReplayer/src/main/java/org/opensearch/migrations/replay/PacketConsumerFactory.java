package org.opensearch.migrations.replay;

import org.opensearch.migrations.replay.datahandlers.IPacketFinalizingConsumer;
import org.opensearch.migrations.replay.datatypes.UniqueReplayerRequestKey;

public interface PacketConsumerFactory<R> {
    IPacketFinalizingConsumer<R> create(UniqueReplayerRequestKey requestKey);
}

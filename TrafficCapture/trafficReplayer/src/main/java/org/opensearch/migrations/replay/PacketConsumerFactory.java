package org.opensearch.migrations.replay;

import org.opensearch.migrations.replay.datahandlers.IPacketFinalizingConsumer;
import org.opensearch.migrations.replay.datatypes.UniqueReplayerRequestKey;
import org.opensearch.migrations.replay.tracing.RequestContext;

public interface PacketConsumerFactory<R> {
    IPacketFinalizingConsumer<R> create(UniqueReplayerRequestKey requestKey, RequestContext context);
}

package org.opensearch.migrations.replay;

import org.opensearch.migrations.replay.datahandlers.IPacketFinalizingConsumer;

public interface PacketConsumerFactory<R> {
    IPacketFinalizingConsumer<R> create();
}

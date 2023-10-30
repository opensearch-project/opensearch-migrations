package org.opensearch.migrations.replay.traffic.source;

import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface ITrafficCaptureSource extends Closeable {

    CompletableFuture<List<ITrafficStreamWithKey>> readNextTrafficStreamChunk();

    default void commitTrafficStream(ITrafficStreamKey trafficStreamKey) throws IOException {}

    default void close() throws IOException {}
}

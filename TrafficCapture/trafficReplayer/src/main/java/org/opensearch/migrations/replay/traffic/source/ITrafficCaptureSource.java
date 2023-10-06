package org.opensearch.migrations.replay.traffic.source;

import java.io.Closeable;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface ITrafficCaptureSource extends Closeable {

    CompletableFuture<List<ITrafficStreamWithKey>> readNextTrafficStreamChunk();
}

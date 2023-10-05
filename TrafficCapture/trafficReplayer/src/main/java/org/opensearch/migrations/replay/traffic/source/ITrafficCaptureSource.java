package org.opensearch.migrations.replay.traffic.source;

import org.opensearch.migrations.replay.datatypes.TrafficStreamKey;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import java.io.Closeable;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface ITrafficCaptureSource extends Closeable {

    CompletableFuture<List<ITrafficStreamWithKey>> readNextTrafficStreamChunk();
}

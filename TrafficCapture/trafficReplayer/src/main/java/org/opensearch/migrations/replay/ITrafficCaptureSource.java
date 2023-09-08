package org.opensearch.migrations.replay;

import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import java.io.Closeable;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface ITrafficCaptureSource extends Closeable {

    CompletableFuture<List<TrafficStream>> readNextTrafficStreamChunk();
}

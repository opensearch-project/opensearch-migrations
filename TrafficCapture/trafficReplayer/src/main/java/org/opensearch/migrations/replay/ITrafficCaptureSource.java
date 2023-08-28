package org.opensearch.migrations.replay;

import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import java.io.Closeable;
import java.util.function.Consumer;
import java.util.stream.Stream;

public interface ITrafficCaptureSource extends Closeable {

    boolean readNextChunk(Consumer<TrafficStream> trafficStreamConsumer);
}

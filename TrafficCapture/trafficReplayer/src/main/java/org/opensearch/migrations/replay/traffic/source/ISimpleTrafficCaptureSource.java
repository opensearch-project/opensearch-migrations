package org.opensearch.migrations.replay.traffic.source;

import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;

import java.io.IOException;

public interface ISimpleTrafficCaptureSource extends ITrafficCaptureSource {
    void commitTrafficStream(ITrafficStreamKey trafficStreamKey) throws IOException;
}

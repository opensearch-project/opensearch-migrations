package org.opensearch.migrations.replay.traffic.source;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.opensearch.migrations.tracing.TestContext;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ArrayCursorTrafficSourceContext implements Function<TestContext, ISimpleTrafficCaptureSource> {
    public final List<TrafficStream> trafficStreamsList;
    public final AtomicInteger nextReadCursor = new AtomicInteger();

    public ArrayCursorTrafficSourceContext(List<TrafficStream> trafficStreamsList) {
        this.trafficStreamsList = trafficStreamsList;
    }

    public ISimpleTrafficCaptureSource apply(TestContext rootContext) {
        var rval = new ArrayCursorTrafficCaptureSource(rootContext, this);
        log.info(
            "trafficSource=" + rval + " readCursor=" + rval.readCursor.get() + " nextReadCursor=" + nextReadCursor.get()
        );
        return rval;
    }
}

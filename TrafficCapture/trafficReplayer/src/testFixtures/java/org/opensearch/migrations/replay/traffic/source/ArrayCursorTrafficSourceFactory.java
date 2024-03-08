package org.opensearch.migrations.replay.traffic.source;

import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.tracing.TestContext;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

@Slf4j
public class ArrayCursorTrafficSourceFactory implements Function<TestContext, ISimpleTrafficCaptureSource> {
    public final List<TrafficStream> trafficStreamsList;
    public final AtomicInteger nextReadCursor = new AtomicInteger();

    public ArrayCursorTrafficSourceFactory(List<TrafficStream> trafficStreamsList) {
        this.trafficStreamsList = trafficStreamsList;
    }

    public ISimpleTrafficCaptureSource apply(TestContext rootContext) {
        var rval = new ArrayCursorTrafficCaptureSource(rootContext, this);
        log.info("trafficSource="+rval+" readCursor="+rval.readCursor.get()+" nextReadCursor="+ nextReadCursor.get());
        return rval;
    }
}

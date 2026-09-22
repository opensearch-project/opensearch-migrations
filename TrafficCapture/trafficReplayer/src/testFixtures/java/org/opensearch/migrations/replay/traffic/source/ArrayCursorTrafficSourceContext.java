package org.opensearch.migrations.replay.traffic.source;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.opensearch.migrations.tracing.TestContext;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ArrayCursorTrafficSourceContext implements Function<TestContext, ISimpleTrafficCaptureSource> {
    record Activation(int sourceGeneration, int startingCursor) {}

    public final List<TrafficStream> trafficStreamsList;
    public final AtomicInteger nextReadCursor = new AtomicInteger();
    private final AtomicInteger nextSourceGeneration;
    private ArrayCursorTrafficCaptureSource activeSource;

    public ArrayCursorTrafficSourceContext(
        List<TrafficStream> trafficStreamsList,
        int sourceGeneration
    ) {
        if (sourceGeneration < 0) {
            throw new IllegalArgumentException("sourceGeneration must not be negative");
        }
        this.trafficStreamsList = trafficStreamsList;
        this.nextSourceGeneration = new AtomicInteger(sourceGeneration);
    }

    public ISimpleTrafficCaptureSource apply(TestContext rootContext) {
        var rval = new ArrayCursorTrafficCaptureSource(rootContext, this);
        log.info(
            "trafficSource=" + rval + " readCursor=" + rval.readCursor.get() + " nextReadCursor=" + nextReadCursor.get()
        );
        return rval;
    }

    synchronized Activation prepareActivation() {
        if (activeSource != null) {
            activeSource.retireForSupersession();
        }
        return new Activation(
            nextSourceGeneration.getAndIncrement(),
            nextReadCursor.get()
        );
    }

    synchronized void publishActivation(ArrayCursorTrafficCaptureSource source) {
        activeSource = source;
    }
}

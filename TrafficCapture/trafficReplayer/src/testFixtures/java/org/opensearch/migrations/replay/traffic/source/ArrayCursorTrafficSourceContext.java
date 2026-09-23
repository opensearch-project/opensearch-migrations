package org.opensearch.migrations.replay.traffic.source;

// REBUILD-LIMBO(G10) -- nothing in this file is live yet. Javadoc is left outside the marked
// regions so it needs no escaping and keeps its blame; it documents code that is not compiled.
// Resolve each region to dead, keep, or refactor deliberately. If a member is deleted, delete its
// javadoc with it. See AGENTS.md section 8a.
// Test carried byte-identical. Unresolved: ISimpleTrafficCaptureSource . Per AGENTS.md section 4 an inherited test may stay broken while the architectures are partly connected; this one is restored by the milestone that rebuilds its subject, keeping its assertions conceptually stable while changing the mechanics.
// Un-mark a member by deleting the delimiter lines around it and splitting this region; the
// code between them is verbatim, so blame survives. Read this before writing anything new

// REBUILD-LIMBO-START(G10)
/*

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

*/
// REBUILD-LIMBO-END(G10)